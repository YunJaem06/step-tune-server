package hs.project.steptune.steptuneserver.auth

import com.fasterxml.jackson.annotation.JsonProperty
import hs.project.steptune.steptuneserver.config.AuthProperties
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

/**
 * Android Naver SDK에서 받은 Access Token으로 Naver 사용자 프로필 API를 호출한다.
 * 서버 전용 Client ID/Secret도 함께 보내 토큰과 Naver 애플리케이션 설정을 검증한다.
 */
@Component
class NaverAccessTokenVerifier(
    /** Naver 서버 전용 Client ID/Secret을 환경변수에서 읽어 제공한다. */
    private val properties: AuthProperties,
    /** Spring이 관리하는 공통 HTTP 설정을 복제해 Naver 전용 RestClient를 만든다. */
    restClientBuilder: RestClient.Builder,
) : SocialTokenVerifier {
    /** 레지스트리가 이 구현체를 NAVER 요청에 연결하는 식별값이다. */
    override val provider = SocialProvider.NAVER

    /** Naver Open API 공통 도메인을 사용하는 HTTP 클라이언트다. */
    private val restClient = restClientBuilder.clone()
        .baseUrl("https://openapi.naver.com")
        .build()

    /** Naver 서버가 확인한 사용자 ID와 이메일을 공통 SocialIdentity로 변환한다. */
    override fun verify(token: String): SocialIdentity {
        // Client Secret은 Android 앱이 아니라 서버 환경변수에서만 읽는다.
        val clientId = properties.naverClientId?.takeIf { it.isNotBlank() }
            ?: throw SocialProviderNotConfiguredException(provider)
        val clientSecret = properties.naverClientSecret?.takeIf { it.isNotBlank() }
            ?: throw SocialProviderNotConfiguredException(provider)

        // Access Token과 서버 자격 증명으로 실제 Naver 사용자 정보를 조회한다.
        val userInfo = request(token, clientId, clientSecret)
        val profile = userInfo.response
        if (userInfo.resultCode != "00" || profile?.id.isNullOrBlank()) {
            throw InvalidAuthTokenException("Naver access token is invalid")
        }

        // Naver 응답의 id가 외부 계정을 계속 식별하는 providerSubject가 된다.
        return SocialIdentity(
            provider = provider,
            subject = requireNotNull(profile).id,
            email = profile.email,
        )
    }

    /**
     * Naver 프로필 API를 호출하고 HTTP 오류를 내부 인증 예외로 변환한다.
     * 400/401은 토큰 문제, 그 외 HTTP/통신 실패는 재시도 가능한 외부 장애로 본다.
     */
    private fun request(token: String, clientId: String, clientSecret: String): NaverUserInfoResponse {
        try {
            return restClient.get()
                .uri("/v1/nid/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header("X-Naver-Client-Id", clientId)
                .header("X-Naver-Client-Secret", clientSecret)
                .retrieve()
                .body(NaverUserInfoResponse::class.java)
                ?: throw SocialVerificationUnavailableException(
                    provider,
                    IllegalStateException("Naver returned an empty response"),
                )
        } catch (exception: RestClientResponseException) {
            if (exception.statusCode.value() == 400 || exception.statusCode.value() == 401) {
                throw InvalidAuthTokenException("Naver access token is invalid")
            }
            throw SocialVerificationUnavailableException(provider, exception)
        } catch (exception: RestClientException) {
            throw SocialVerificationUnavailableException(provider, exception)
        }
    }
}

/** Naver API의 공통 결과 코드와 실제 사용자 응답을 매핑한다. */
private data class NaverUserInfoResponse(
    /** `00`이면 Naver API가 요청을 정상 처리했다는 의미다. */
    @param:JsonProperty("resultcode")
    val resultCode: String? = null,
    /** Naver가 함께 내려주는 결과 설명이며 현재 로그인 키로는 사용하지 않는다. */
    val message: String? = null,
    /** 성공 시 포함되는 실제 사용자 프로필 객체다. */
    val response: NaverProfileResponse? = null,
)

/** 로그인 연결에 필요한 Naver 사용자 고유 ID와 참고 이메일만 받는다. */
private data class NaverProfileResponse(
    /** 변경되지 않는 Naver 사용자 고유 ID로 providerSubject에 저장한다. */
    val id: String,
    /** 사용자가 제공에 동의한 경우에만 내려오는 참고 이메일이다. */
    val email: String? = null,
)
