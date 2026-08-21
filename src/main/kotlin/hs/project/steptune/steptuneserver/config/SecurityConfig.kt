package hs.project.steptune.steptuneserver.config

import hs.project.steptune.steptuneserver.common.ApiResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.web.SecurityFilterChain
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * 어떤 API를 공개하고 어떤 API에 JWT 인증을 요구할지 정의한다.
 * 또한 Step Tune JWT의 서명기와 검증기를 같은 서버 비밀키로 구성한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties::class)
class SecurityConfig {

    /** 모든 HTTP 요청에 적용되는 Spring Security 필터 체인을 구성한다. */
    @Bean
    fun securityFilterChain(http: HttpSecurity, objectMapper: ObjectMapper): SecurityFilterChain {
        // 인증 실패가 Spring 기본 HTML이 아니라 앱 공통 JSON 형식으로 내려가게 만든다.
        val authenticationEntryPoint = { response: HttpServletResponse ->
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json"
            response.characterEncoding = Charsets.UTF_8.name()
            objectMapper.writeValue(
                response.outputStream,
                ApiResponse<Nothing>(
                    code = HttpServletResponse.SC_UNAUTHORIZED,
                    message = "Authentication is required",
                    data = null,
                ),
            )
        }

        http
            // 서버가 쿠키 세션 인증을 쓰지 않고 Bearer Token을 쓰므로 CSRF 보호 대상이 아니다.
            .csrf { it.disable() }
            // HTTP 세션을 만들지 않고 매 요청의 JWT만으로 인증하는 무상태 서버로 설정한다.
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { authorize ->
                authorize
                    // 로그인·자동 로그인·로그아웃은 아직 Access Token이 없어도 호출할 수 있어야 한다.
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/google",
                        "/api/v1/auth/social",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/logout",
                    ).permitAll()
                    // 서버 운영 상태 확인 엔드포인트는 인증 없이 접근할 수 있게 한다.
                    .requestMatchers("/actuator/health").permitAll()
                    // 위 목록 이외의 API는 모두 유효한 Step Tune Access Token이 필요하다.
                    .anyRequest().authenticated()
            }
            // 보호 API에 토큰 없이 접근했을 때 공통 401 JSON을 반환한다.
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint { _, response, _ -> authenticationEntryPoint(response) }
            }
            // Authorization: Bearer JWT를 읽고 아래 JwtDecoder로 검증하는 Resource Server 기능이다.
            .oauth2ResourceServer { oauth2 ->
                oauth2
                    .jwt(Customizer.withDefaults())
                    .authenticationEntryPoint { _, response, _ -> authenticationEntryPoint(response) }
            }

        return http.build()
    }

    /** 설정 문자열을 HS256 JWT 서명과 검증에 사용할 HMAC 비밀키 객체로 변환한다. */
    @Bean
    fun jwtSecretKey(properties: AuthProperties): SecretKey =
        SecretKeySpec(properties.jwtSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")

    /** AuthService가 Access Token을 발급할 때 사용하는 HS256 JWT 인코더다. */
    @Bean
    fun jwtEncoder(secretKey: SecretKey): JwtEncoder =
        NimbusJwtEncoder.withSecretKey(secretKey)
            .algorithm(MacAlgorithm.HS256)
            .build()

    /** 보호 API 요청의 JWT 서명과 표준/커스텀 claim을 검증하는 디코더다. */
    @Bean
    fun jwtDecoder(secretKey: SecretKey, properties: AuthProperties): JwtDecoder {
        // 우선 같은 서버 비밀키로 HS256 서명이 올바른지 검증한다.
        val decoder = NimbusJwtDecoder.withSecretKey(secretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()

        // 다른 서비스나 앱을 대상으로 발급된 토큰이 아닌지 audience를 확인한다.
        val audienceValidator = OAuth2TokenValidator<Jwt> { jwt ->
            if (jwt.audience?.contains(properties.jwtAudience) == true) {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(
                    OAuth2Error("invalid_token", "The token audience is invalid", null),
                )
            }
        }

        // Refresh Token 등 다른 종류의 토큰을 Access Token 자리에 쓰지 못하게 한다.
        val tokenUseValidator = OAuth2TokenValidator<Jwt> { jwt ->
            if (jwt.getClaimAsString("token_use") == "access") {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(
                    OAuth2Error("invalid_token", "The token is not an access token", null),
                )
            }
        }

        // 기본 만료시간/issuer 검사에 Step Tune 전용 audience와 token_use 검사를 합친다.
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(properties.jwtIssuer),
                audienceValidator,
                tokenUseValidator,
            ),
        )
        return decoder
    }
}
