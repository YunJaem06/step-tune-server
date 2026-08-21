package hs.project.steptune.steptuneserver.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * application.properties의 `app.auth.*` 값을 타입 안전하게 묶는다.
 * 필수값이 없거나 JWT Secret이 너무 짧으면 서버 시작 단계에서 바로 실패시킨다.
 */
@Validated
@ConfigurationProperties("app.auth")
data class AuthProperties(
    /** Google ID Token audience 검증에 사용하는 Web OAuth Client ID다. */
    @field:NotBlank
    val googleClientId: String,
    /** Kakao Access Token이 Step Tune 앱용인지 확인하는 숫자 App ID다. */
    val kakaoAppId: Long? = null,
    /** Naver 프로필 API 호출에 사용하는 서버 설정값이다. */
    val naverClientId: String? = null,
    /** Android 앱이나 Git에 포함하면 안 되는 Naver 서버 전용 비밀값이다. */
    val naverClientSecret: String? = null,
    /** Step Tune JWT를 HS256으로 서명하는 최소 32문자 서버 비밀값이다. */
    @field:NotBlank
    @field:Size(min = 32)
    val jwtSecret: String,
    /** 이 서버가 JWT를 발급했다는 것을 표시하고 검증하는 issuer 값이다. */
    val jwtIssuer: String = "https://step-tune.local",
    /** Step Tune Android 앱용 JWT인지 구분하는 audience 값이다. */
    val jwtAudience: String = "step-tune-android",
    /** 일반 API용 Access Token의 유효기간이다. 기본값은 15분이다. */
    val accessTokenTtl: Duration = Duration.ofMinutes(15),
    /** 자동 로그인용 Refresh Token 세션의 유효기간이다. 기본값은 30일이다. */
    val refreshTokenTtl: Duration = Duration.ofDays(30),
)
