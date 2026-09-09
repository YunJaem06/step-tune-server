package hs.project.steptune.steptuneserver.recommendation

/** 비활성/키 누락/외부 장애를 나타낸다. Google의 원문 메시지나 API 키는 예외에 넣지 않는다. */
class MusicRecommendationUnavailableException(
    message: String = "Music recommendation engine is not configured",
) : RuntimeException(message)

/** 무료 호출 한도를 포함한 외부 AI의 429 응답이다. 서버는 자동 재시도하지 않는다. */
class MusicRecommendationRateLimitException :
    RuntimeException("Music recommendation quota exceeded; please try again later")

/** 잘린 응답, 안전 필터 차단, 잘못된 JSON 등으로 앱에 사용할 추천을 만들 수 없을 때 발생한다. */
class MusicRecommendationInvalidResponseException :
    RuntimeException("Music recommendation engine returned an invalid response")
