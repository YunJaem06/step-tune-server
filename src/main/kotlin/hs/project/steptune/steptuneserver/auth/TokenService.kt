package hs.project.steptune.steptuneserver.auth

import hs.project.steptune.steptuneserver.config.AuthProperties
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** 생성된 Access Token 원문과 정확한 만료 시각을 함께 전달하는 내부 값 객체다. */
data class AccessToken(
    /** Android가 Authorization Bearer 헤더에 넣을 서명 완료 JWT 문자열이다. */
    val value: String,
    /** 응답의 expiresIn을 계산하고 서버가 만료 claim을 만들 때 사용한 절대 UTC 시각이다. */
    val expiresAt: Instant,
)

/** Refresh Token 원문과 DB에 저장할 SHA-256 해시를 함께 전달하는 내부 값 객체다. */
data class RefreshToken(
    /** Android의 안전한 저장소에 전달하며 서버 DB에는 남기지 않는 원문이다. */
    val value: String,
    /** 원문 노출 없이 이후 요청 토큰을 조회하기 위해 DB에 저장하는 값이다. */
    val hash: String,
)

/**
 * Step Tune 자체 Access/Refresh Token을 생성하고 Refresh Token을 해시한다.
 * 소셜 제공자 토큰은 최초 신원 확인에만 쓰고, 이후 앱 API는 이 서비스가 만든 토큰을 사용한다.
 */
@Service
class TokenService(
    /** SecurityConfig가 만든 HS256 인코더로 JWT header와 claim에 서명한다. */
    private val jwtEncoder: JwtEncoder,
    /** issuer, audience, 유효기간 같은 인증 설정을 제공한다. */
    private val properties: AuthProperties,
) {
    /** Refresh Token을 예측할 수 없게 만드는 보안 난수 생성기다. */
    private val secureRandom = SecureRandom()

    /** 서버 지역 시간과 무관하게 토큰 시각을 UTC 기준으로 계산한다. */
    private val clock: Clock = Clock.systemUTC()

    /**
     * 일반 API 인증에 사용할 HS256 서명 JWT를 생성한다.
     * subject에는 내부 userId를 넣고 issuer, audience, token_use도 함께 넣어 용도 혼동을 막는다.
     */
    fun createAccessToken(userId: UUID, provider: SocialProvider): AccessToken {
        val issuedAt = Instant.now(clock)
        val expiresAt = issuedAt.plus(properties.accessTokenTtl)

        // JWT payload: 서버와 Spring Security가 나중에 검증할 사용자/용도/시간 정보다.
        val claims = JwtClaimsSet.builder()
            .issuer(properties.jwtIssuer)
            .subject(userId.toString())
            .audience(listOf(properties.jwtAudience))
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("token_use", "access")
            .claim("auth_provider", provider.name)
            .build()

        // JWT header: HMAC SHA-256으로 서명된 JWT임을 명시한다.
        val headers = JwsHeader.with(MacAlgorithm.HS256)
            .type("JWT")
            .build()
        val value = jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).tokenValue

        return AccessToken(value = value, expiresAt = expiresAt)
    }

    /**
     * 256비트 보안 난수로 불투명한 Refresh Token을 만든다.
     * URL-safe Base64를 사용해 JSON과 HTTP에서 별도 이스케이프 없이 안전하게 전달한다.
     */
    fun createRefreshToken(): RefreshToken {
        // 32바이트는 256비트이며 무차별 추측이 현실적으로 불가능한 충분한 난수 공간이다.
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        // URL-safe 형식은 +, / 문자를 피하고 padding을 없애 HTTP/JSON 전달을 단순하게 한다.
        val value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return RefreshToken(value = value, hash = hashRefreshToken(value))
    }

    /**
     * Refresh Token 원문을 SHA-256 64자리 16진 문자열로 변환한다.
     * DB가 유출돼도 저장된 값만으로 원문 Refresh Token을 바로 사용할 수 없게 한다.
     */
    fun hashRefreshToken(value: String): String {
        // 같은 원문은 항상 같은 해시가 되므로 인덱스가 있는 DB 컬럼으로 세션을 찾을 수 있다.
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        // 각 바이트를 두 자리 16진수로 바꿔 CHAR/VARCHAR에 저장 가능한 64자리 문자열을 만든다.
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
