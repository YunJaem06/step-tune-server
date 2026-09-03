package hs.project.steptune.steptuneserver.step

import java.math.BigDecimal
import java.time.LocalDate

/** AI 추천과 Android 통계 화면에서 함께 사용할 기준일 포함 최근 7일 걸음 통계다. */
data class WeeklyStepStatisticsData(
    /** 사용자가 요청한 통계 기준 날짜다. */
    val recordDate: LocalDate,
    /** 기준 날짜에 서버 DB에 저장된 하루 총걸음 수다. */
    val todayStepCount: Int,
    /** 기준 날짜를 포함한 최근 7일 중 실제 기록이 있는 날짜들의 평균 걸음 수다. */
    val recent7DayAverage: BigDecimal,
    /** 최근 7일 범위에서 서버 기록이 실제로 존재하는 날짜 수다. */
    val recordedDayCount: Int,
    /** 기준 날짜 걸음 수에서 최근 7일 평균을 뺀 값이다. */
    val differenceFromAverage: BigDecimal,
    /** 최근 7일 평균 대비 기준 날짜의 증감률이며 평균이 0이면 계산할 수 없어 null이다. */
    val changeRatePercent: BigDecimal?,
)
