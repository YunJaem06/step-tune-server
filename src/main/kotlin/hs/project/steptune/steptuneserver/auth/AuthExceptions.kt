package hs.project.steptune.steptuneserver.auth

/** 토큰이 위조·만료·폐기되었거나 요청한 제공자와 일치하지 않을 때 발생한다. */
class InvalidAuthTokenException(message: String) : RuntimeException(message)

/** 사용자 잘못이 아니라 소셜 제공자 API의 장애나 네트워크 실패일 때 발생한다. */
class SocialVerificationUnavailableException(
    /** 어느 외부 제공자에서 장애가 발생했는지 오류 응답과 로그에서 구분하는 값이다. */
    val provider: SocialProvider,
    /** 네트워크 또는 외부 API의 실제 원인을 서버 로그에 보존한다. */
    cause: Throwable,
) : RuntimeException("$provider token verification is temporarily unavailable", cause)

/** 해당 소셜 로그인을 검증할 서버 환경변수가 아직 설정되지 않았을 때 발생한다. */
class SocialProviderNotConfiguredException(
    /** 어떤 제공자의 Client ID/Secret/App ID가 빠졌는지 나타낸다. */
    val provider: SocialProvider,
) : RuntimeException("$provider login is not configured")

/** JWT에는 사용자가 있지만 DB에서 해당 사용자를 찾을 수 없을 때 발생한다. */
class UserNotFoundException : RuntimeException("User not found")
