package hs.project.steptune.steptuneserver.recommendation

/** 외부 AI는 추천 내용만 생성한다. 사용자 ID, DB 엔티티, JWT를 이 경계 밖으로 전달하지 않는다. */
interface MusicRecommendationClient {
    /** DB에서 조회한 통계 스냅샷과 검증된 취향으로 추천 내용을 생성한다. */
    fun recommend(
        summary: RecommendationStepSummaryData,
        request: GenerateMusicRecommendationRequest,
    ): AiMusicRecommendation
}

/** AI에서 검증해 받은 내용이다. UUID/날짜/통계/생성 시각은 AI가 아니라 서버가 붙인다. */
data class AiMusicRecommendation(
    /** 실제 건강 상태가 아닌 음악 선택용 상대 활동 수준이다. */
    val activityLevel: RecommendationActivityLevel,
    /** 짧은 한국어 추천 이유다. */
    val reason: String,
    /** Gemini가 실제 발매곡이라고 판단해 추천한 정확히 한 곡의 제목이다. */
    val trackTitle: String,
    /** 추천 곡의 대표 가수 또는 아티스트명이다. */
    val trackArtist: String,
)
