package hs.project.steptune.steptuneserver.step

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** Android가 오프라인 기간을 포함한 여러 날짜의 걸음 총합을 한 번에 동기화하는 요청이다. */
data class SyncDailyStepRecordsRequest(
    /** 요청 한 번에 최소 1건, 최대 366일을 보내 과도한 단일 요청을 방지한다. */
    @field:NotEmpty
    @field:Size(max = 366)
    val records: List<@Valid DailyStepRecordWriteRequest>,
)

/** 한 날짜에 대해 Android가 서버에 저장할 최신 측정값이다. */
data class DailyStepRecordWriteRequest(
    /** 휴대폰 현지 시간대에서 이 걸음 수를 집계한 `YYYY-MM-DD` 날짜다. */
    val recordDate: LocalDate,
    /** 해당 날짜의 누적 총걸음 수이며 음수는 요청 검증에서 거절한다. */
    @field:PositiveOrZero
    val stepCount: Int,
    /** 마지막 측정 시각과 UTC 오프셋을 함께 보내 서버가 정확한 Instant로 저장하게 한다. */
    val measuredAt: OffsetDateTime,
)

/** 서버 DB에 최종 저장된 하루 걸음 기록의 외부 응답 형태다. */
data class DailyStepRecordData(
    /** 사용자의 현지 집계 날짜다. */
    val recordDate: LocalDate,
    /** 그 날짜에 서버가 저장한 가장 큰 누적 총걸음 수다. */
    val stepCount: Int,
    /** 앱에서 마지막으로 측정한 시각이다. */
    val measuredAt: Instant,
    /** 서버에서 이 행을 마지막으로 저장한 시각이다. */
    val updatedAt: Instant,
)

/** 동기화 완료 후 앱이 서버의 최종값과 동기화 시각을 확인하는 응답이다. */
data class DailyStepRecordSyncData(
    /** 요청 날짜순이 아니라 날짜 오름차순으로 정렬한 최종 저장 결과다. */
    val records: List<DailyStepRecordData>,
    /** 이 요청을 DB에 반영한 서버 UTC 시각이다. */
    val syncTime: Instant,
)

/** 특정 날짜 조회에서 기록이 아직 없는 상태도 정상 결과로 표현하는 응답이다. */
data class DailyStepRecordLookupData(
    /** 저장된 기록이 없으면 null이며, 이는 걸음 수 0을 저장한 경우와 구분된다. */
    val record: DailyStepRecordData?,
)

/** 기간 조회 결과와 실제 적용된 조회 범위를 함께 반환한다. */
data class DailyStepRecordHistoryData(
    /** 조회 시작일이며 결과에 포함된다. */
    val from: LocalDate,
    /** 조회 종료일이며 결과에 포함된다. */
    val to: LocalDate,
    /** 기록이 있는 날짜만 날짜 오름차순으로 포함한다. */
    val records: List<DailyStepRecordData>,
)
