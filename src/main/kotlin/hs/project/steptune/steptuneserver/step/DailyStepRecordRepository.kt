package hs.project.steptune.steptuneserver.step

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

/** 일별 걸음 엔티티를 사용자와 날짜 조건으로 조회·저장하는 JPA 저장소다. */
interface DailyStepRecordRepository : JpaRepository<DailyStepRecordEntity, Long> {
    /** 동기화 요청에 포함된 날짜들의 기존 행을 한 번에 읽어 신규/수정을 구분한다. */
    fun findAllByUserIdAndRecordDateIn(
        userId: Long,
        recordDates: Collection<LocalDate>,
    ): List<DailyStepRecordEntity>

    /** 현재 사용자의 특정 날짜 기록 한 건을 조회한다. */
    fun findByUserIdAndRecordDate(
        userId: Long,
        recordDate: LocalDate,
    ): DailyStepRecordEntity?

    /** 시작일과 종료일을 모두 포함해 날짜 오름차순으로 걸음 기록을 조회한다. */
    fun findAllByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
        userId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<DailyStepRecordEntity>
}

