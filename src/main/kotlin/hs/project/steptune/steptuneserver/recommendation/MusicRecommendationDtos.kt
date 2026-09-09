package hs.project.steptune.steptuneserver.recommendation

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Android가 로그인 사용자의 걸음 기록을 기준으로 음악 추천을 요청하는 JSON 형식이다. */
data class GenerateMusicRecommendationRequest(
    /** 서버에서 추천 근거로 조회할 사용자의 현지 날짜다. */
    val recordDate: LocalDate,
    /** Android에 저장된 선호 분위기이며 선택 사항이고 최대 2개까지 허용한다. */
    @field:Size(max = 2)
    val preferredMoods: List<MusicMood> = emptyList(),
    /** 선호 장르는 선택 사항이고 한 요청에 최대 3개까지 허용한다. */
    @field:Size(max = 3)
    val preferredGenres: List<MusicGenre> = emptyList(),
    /** 사용자가 음악을 들을 세션 길이이며 10분에서 120분으로 제한한다. 한 곡의 재생 길이라는 뜻은 아니다. */
    @field:Min(10)
    @field:Max(120)
    val durationMinutes: Int = 30,
)

/** 추천 당시 서버가 사용한 걸음 통계를 Android가 확인할 수 있는 응답 형태다. */
data class RecommendationStepSummaryData(
    /** 추천 기준 날짜의 하루 총걸음 수다. */
    val todayStepCount: Int,
    /** 기준일 포함 최근 7일 중 실제 기록이 있는 날짜들의 평균 걸음 수다. */
    val recent7DayAverage: BigDecimal,
    /** 최근 7일 평균 계산에 실제로 사용한 날짜 수다. */
    val recordedDayCount: Int,
    /** 기준 날짜 걸음 수에서 최근 7일 평균을 뺀 값이다. */
    val differenceFromAverage: BigDecimal,
    /** 평균이 0이면 null이고, 그 외에는 평균 대비 기준일 증감률이다. */
    val changeRatePercent: BigDecimal?,
)

/** Gemini가 추천한 정확히 한 곡과 Android가 YouTube 검색에 사용할 값을 묶는다. */
data class RecommendedTrackData(
    /** 실제 발매된 곡의 제목이다. */
    val title: String,
    /** 해당 곡의 대표 가수 또는 아티스트명이다. */
    val artist: String,
    /** AI 자유 문장이 아니라 서버가 artist + title + official audio 형식으로 만든 검색어다. */
    val searchQuery: String,
)

/** 서버가 저장하지 않고 Android Room에 바로 보관할 수 있도록 완성된 AI 추천 결과를 반환한다. */
data class MusicRecommendationData(
    /** 서버 DB PK가 아니라 요청마다 서버가 생성하고 Android Room에서 사용할 UUID 문자열이다. */
    val recommendationId: String,
    /** 추천 근거가 된 사용자의 현지 날짜다. */
    val recordDate: LocalDate,
    /** 추천 당시의 걸음 통계 스냅샷이다. */
    val stepSummary: RecommendationStepSummaryData,
    /** AI가 기준일 걸음과 최근 평균을 비교해 분류한 활동 수준이다. */
    val activityLevel: RecommendationActivityLevel,
    /** 요청한 추천 재생 시간이다. */
    val durationMinutes: Int,
    /** AI가 걸음 통계와 취향을 바탕으로 작성한 짧은 추천 설명이다. */
    val reason: String,
    /** Gemini가 조건에 맞춰 고른 한 곡이다. 한 응답에 목록이나 플레이리스트를 포함하지 않는다. */
    val track: RecommendedTrackData,
    /** 추천 결과가 서버에서 생성된 UTC 시각이다. */
    val generatedAt: Instant,
)
