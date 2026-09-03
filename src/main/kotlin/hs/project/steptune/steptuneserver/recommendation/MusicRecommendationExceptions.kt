package hs.project.steptune.steptuneserver.recommendation

/** API 계약은 열려 있지만 실제 AI 추천 Service가 아직 연결되지 않았을 때 발생한다. */
class MusicRecommendationUnavailableException :
    RuntimeException("Music recommendation engine is not configured")
