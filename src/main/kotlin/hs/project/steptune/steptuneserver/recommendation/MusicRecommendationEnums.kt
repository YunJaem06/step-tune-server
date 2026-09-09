package hs.project.steptune.steptuneserver.recommendation

/** 사용자가 음악 추천 화면에서 선택할 수 있는 분위기다. */
enum class MusicMood {
    CALM,
    ENERGETIC,
    EMOTIONAL,
    FOCUSED,
    LIVELY,
}

/** 첫 추천 버전에서 지원하는 음악 장르다. */
enum class MusicGenre {
    BALLAD,
    HIP_HOP,
    RNB,
    POP,
    ROCK,
    INDIE,
    JAZZ,
    CLASSICAL,
}

/** 기준일 걸음과 최근 평균을 바탕으로 AI가 분류해 주는 사용자의 활동 수준이다. */
enum class RecommendationActivityLevel {
    LOW,
    MODERATE,
    HIGH,
}
