package hs.project.steptune.steptuneserver.recommendation

/** Controller와 향후 AI 추천 구현 사이의 고정된 추천 생성 계약이다. */
fun interface GenerateMusicRecommendationUseCase {
    /** 로그인 사용자의 걸음과 취향을 분석한 결과를 반환하며 서버 DB에는 추천 기록을 저장하지 않는다. */
    fun generate(userId: Long, request: GenerateMusicRecommendationRequest): MusicRecommendationData
}
