package hs.project.steptune.steptuneserver.step

import hs.project.steptune.steptuneserver.auth.UserNotFoundException
import hs.project.steptune.steptuneserver.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * 일별 걸음 수의 동기화와 조회 규칙을 담당한다.
 * Controller는 HTTP/JWT 처리만 하고 사용자 확인, upsert, 날짜 검증은 이 Service에서 수행한다.
 */
@Service
class StepRecordService(
    /** 사용자 존재 확인과 같은 사용자 동기화 요청 직렬화를 담당한다. */
    private val userRepository: UserRepository,
    /** 일별 걸음 기록을 날짜 조건으로 조회·저장한다. */
    private val dailyStepRecordRepository: DailyStepRecordRepository,
) {
    /** 앱과 서버 위치에 관계없이 서버 생성·수정 시각은 UTC로 기록한다. */
    private val clock: Clock = Clock.systemUTC()

    /**
     * 요청 날짜들을 한 트랜잭션에서 신규 생성하거나 기존 행보다 큰 총걸음 값으로 갱신한다.
     * `사용자+날짜` 유일 제약과 사용자 행 잠금으로 한 날짜당 한 행만 유지하고 오래된 요청의 값 감소를 막는다.
     */
    @Transactional
    fun syncDailyRecords(userId: Long, request: SyncDailyStepRecordsRequest): DailyStepRecordSyncData {
        rejectDuplicateDates(request.records)
        rejectNegativeStepCounts(request.records)

        // 같은 사용자의 두 동기화가 동시에 신규 행을 만들지 못하도록 트랜잭션 동안 사용자 행을 잠근다.
        val user = userRepository.findByIdForUpdate(userId) ?: throw UserNotFoundException()
        val recordDates = request.records.map { it.recordDate }

        // 날짜마다 SELECT하지 않고 요청에 포함된 기존 기록을 한 쿼리로 가져온다.
        val existingByDate = dailyStepRecordRepository
            .findAllByUserIdAndRecordDateIn(userId, recordDates)
            .associateBy { it.recordDate }
        val now = Instant.now(clock)

        val recordsToSave = request.records.map { requested ->
            val existing = existingByDate[requested.recordDate]
            if (existing == null) {
                // 처음 동기화한 날짜라면 새 행을 만든다.
                DailyStepRecordEntity(
                    user = user,
                    recordDate = requested.recordDate,
                    stepCount = requested.stepCount,
                    measuredAt = requested.measuredAt.toInstant(),
                    createdAt = now,
                    updatedAt = now,
                )
            } else {
                // 두 Android 동기화가 엇갈려 도착할 수 있으므로 기존 총합보다 큰 값만 최신 측정값으로 반영한다.
                existing.apply {
                    if (requested.stepCount > stepCount) {
                        stepCount = requested.stepCount
                        measuredAt = requested.measuredAt.toInstant()
                        updatedAt = now
                    }
                }
            }
        }

        // saveAll과 flush로 요청이 성공하기 전에 INSERT/UPDATE와 DB 제약 검사를 끝낸다.
        val savedRecords = dailyStepRecordRepository.saveAll(recordsToSave)
        dailyStepRecordRepository.flush()
        return DailyStepRecordSyncData(
            records = savedRecords.sortedBy { it.recordDate }.map { it.toData() },
            syncTime = now,
        )
    }

    /** 특정 날짜의 현재 사용자 기록을 조회하며, 없는 날짜는 record=null로 정상 반환한다. */
    @Transactional(readOnly = true)
    fun getDailyRecord(userId: Long, recordDateValue: String): DailyStepRecordLookupData {
        ensureUserExists(userId)
        val recordDate = parseDate(recordDateValue, "recordDate")
        return DailyStepRecordLookupData(
            record = dailyStepRecordRepository.findByUserIdAndRecordDate(userId, recordDate)?.toData(),
        )
    }

    /** 시작일과 종료일을 포함한 최대 366일의 현재 사용자 기록을 날짜순으로 조회한다. */
    @Transactional(readOnly = true)
    fun getHistory(userId: Long, fromValue: String, toValue: String): DailyStepRecordHistoryData {
        ensureUserExists(userId)
        val from = parseDate(fromValue, "from")
        val to = parseDate(toValue, "to")
        val inclusiveDays = ChronoUnit.DAYS.between(from, to) + 1
        if (from.isAfter(to) || inclusiveDays > MAX_HISTORY_DAYS) {
            throw InvalidStepRecordRangeException()
        }

        return DailyStepRecordHistoryData(
            from = from,
            to = to,
            records = dailyStepRecordRepository
                .findAllByUserIdAndRecordDateBetweenOrderByRecordDateAsc(userId, from, to)
                .map { it.toData() },
        )
    }

    /** 요청 내부의 같은 날짜 중복을 DB 작업 전에 찾아 모호한 최종값을 거절한다. */
    private fun rejectDuplicateDates(records: List<DailyStepRecordWriteRequest>) {
        val duplicateDate = records.groupingBy { it.recordDate }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.key
        if (duplicateDate != null) {
            throw DuplicateStepRecordDateException(duplicateDate.toString())
        }
    }

    /** Bean Validation을 거치지 않은 내부 호출도 음수 걸음 값을 저장하지 못하게 한다. */
    private fun rejectNegativeStepCounts(records: List<DailyStepRecordWriteRequest>) {
        if (records.any { it.stepCount < 0 }) {
            throw InvalidStepCountException()
        }
    }

    /** 문자열 날짜를 엄격한 ISO 날짜로 바꾸고 잘못된 값은 공통 400 예외로 변환한다. */
    private fun parseDate(value: String, parameterName: String): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            throw InvalidStepRecordDateException(parameterName)
        }

    /** 탈퇴한 사용자의 만료 전 Access Token으로 빈 기록을 조회하지 못하게 한다. */
    private fun ensureUserExists(userId: Long) {
        if (!userRepository.existsById(userId)) {
            throw UserNotFoundException()
        }
    }

    /** JPA 엔티티를 사용자 소유권이나 내부 ID를 노출하지 않는 API 응답으로 변환한다. */
    private fun DailyStepRecordEntity.toData(): DailyStepRecordData = DailyStepRecordData(
        recordDate = recordDate,
        stepCount = stepCount,
        measuredAt = measuredAt,
        updatedAt = updatedAt,
    )

    private companion object {
        /** 한 번의 기간 조회에서 허용하는 최대 날짜 수다. */
        const val MAX_HISTORY_DAYS = 366L
    }
}
