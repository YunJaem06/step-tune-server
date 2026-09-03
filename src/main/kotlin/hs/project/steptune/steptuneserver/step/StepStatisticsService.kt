package hs.project.steptune.steptuneserver.step

import hs.project.steptune.steptuneserver.auth.UserNotFoundException
import hs.project.steptune.steptuneserver.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** 저장된 일별 걸음 기록을 AI 추천에 사용할 최근 7일 통계로 계산한다. */
@Service
class StepStatisticsService(
    /** 탈퇴한 사용자의 만료 전 Access Token으로 통계를 조회하지 못하게 사용자 존재를 확인한다. */
    private val userRepository: UserRepository,
    /** 현재 사용자의 기준일 및 최근 7일 걸음 기록을 조회한다. */
    private val dailyStepRecordRepository: DailyStepRecordRepository,
) {
    /**
     * 기준 날짜를 포함한 최근 7일 중 실제 저장된 날짜만 사용해 평균과 증감률을 계산한다.
     * 기록이 없는 날짜를 0걸음으로 간주하면 동기화 누락이 활동량 감소로 왜곡되므로 평균에서 제외한다.
     */
    @Transactional(readOnly = true)
    fun getWeeklyStatistics(userId: Long, recordDateValue: String): WeeklyStepStatisticsData {
        ensureUserExists(userId)
        val recordDate = parseDate(recordDateValue)
        val todayRecord = dailyStepRecordRepository.findByUserIdAndRecordDate(userId, recordDate)
            ?: throw StepRecordNotFoundException(recordDate.toString())
        val periodStartDate = recordDate.minusDays(RECENT_PERIOD_DAYS - 1)
        val records = dailyStepRecordRepository
            .findAllByUserIdAndRecordDateBetweenOrderByRecordDateAsc(userId, periodStartDate, recordDate)

        // Int 합계가 커져도 오버플로하지 않고 소수 둘째 자리까지 일정하게 계산하도록 BigDecimal을 사용한다.
        val totalStepCount = records.fold(BigDecimal.ZERO) { total, record ->
            total.add(record.stepCount.toBigDecimal())
        }
        val average = totalStepCount.divide(
            records.size.toBigDecimal(),
            DECIMAL_SCALE,
            RoundingMode.HALF_UP,
        )
        val difference = todayRecord.stepCount.toBigDecimal()
            .subtract(average)
            .setScale(DECIMAL_SCALE, RoundingMode.HALF_UP)
        val changeRate = if (average.signum() == 0) {
            null
        } else {
            difference.multiply(ONE_HUNDRED)
                .divide(average, DECIMAL_SCALE, RoundingMode.HALF_UP)
        }

        return WeeklyStepStatisticsData(
            recordDate = recordDate,
            todayStepCount = todayRecord.stepCount,
            recent7DayAverage = average,
            recordedDayCount = records.size,
            differenceFromAverage = difference,
            changeRatePercent = changeRate,
        )
    }

    /** 쿼리 문자열을 엄격한 ISO 날짜로 변환하고 잘못된 값은 공통 400 응답으로 처리한다. */
    private fun parseDate(value: String): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            throw InvalidStepRecordDateException("recordDate")
        }

    /** JWT의 사용자 ID가 DB에 남아 있는지 확인해 탈퇴 후 접근을 차단한다. */
    private fun ensureUserExists(userId: Long) {
        if (!userRepository.existsById(userId)) {
            throw UserNotFoundException()
        }
    }

    private companion object {
        /** 통계 기준 날짜까지 포함해 조회할 전체 날짜 수다. */
        const val RECENT_PERIOD_DAYS = 7L

        /** 평균과 증감률을 앱에 반환할 소수 자릿수다. */
        const val DECIMAL_SCALE = 2

        /** 비율을 백분율로 변환하기 위한 100 상수다. */
        val ONE_HUNDRED: BigDecimal = BigDecimal.valueOf(100)
    }
}
