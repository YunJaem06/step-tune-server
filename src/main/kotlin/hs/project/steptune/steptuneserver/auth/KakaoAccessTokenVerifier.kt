package hs.project.steptune.steptuneserver.auth

import com.fasterxml.jackson.annotation.JsonProperty
import hs.project.steptune.steptuneserver.config.AuthProperties
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

/**
 * Android Kakao SDK에서 받은 Access Token을 Kakao 서버 API로 재검증한다.
 * 토큰 소유 사용자뿐 아니라 토큰이 Step Tune Kakao 앱용으로 발급됐는지도 확인한다.
 */
@Component
class KakaoAccessTokenVerifier(
    /** Kakao 토큰의 app_id와 비교할 Step Tune Kakao App ID를 제공한다. */
    private val properties: AuthProperties,
    /** Spring이 관리하는 공통 HTTP 설정을 복제해 Kakao 전용 RestClient를 만든다. */
    restClientBuilder: RestClient.Builder,
) : SocialTokenVerifier {
    /** 레지스트리가 이 구현체를 KAKAO 요청에 연결하는 식별값이다. */
    override val provider = SocialProvider.KAKAO

    /** 모든 Kakao 인증 요청이 공통 도메인을 사용하도록 구성한 HTTP 클라이언트다. */
    private val restClient = restClientBuilder.clone()
        .baseUrl("https://kapi.kakao.com")
        .build()

    /** Kakao 토큰 정보와 사용자 정보를 교차 확인한 뒤 공통 SocialIdentity를 만든다. */
    override fun verify(token: String): SocialIdentity {
        // 서버에 Kakao 숫자 App ID가 없으면 토큰이 우리 앱용인지 판단할 수 없으므로 요청하지 않는다.
        val expectedAppId = properties.kakaoAppId
            ?: throw SocialProviderNotConfiguredException(provider)

        // 1. 토큰 정보 API로 만료/유효 여부, 사용자 ID, 발급 대상 App ID를 확인한다.
        val tokenInfo = request<KakaoTokenInfoResponse>(token, "/v1/user/access_token_info")
        if (tokenInfo.appId != expectedAppId || tokenInfo.id == null) {
            throw InvalidAuthTokenException("Kakao access token is invalid")
        }

        // 2. 사용자 정보 API를 별도로 호출하고 두 API가 같은 사용자 ID를 돌려주는지 확인한다.
        val userInfo = request<KakaoUserInfoResponse>(token, "/v2/user/me?secure_resource=true")
        if (userInfo.id == null || userInfo.id != tokenInfo.id) {
            throw InvalidAuthTokenException("Kakao access token is invalid")
        }
        val account = userInfo.kakaoAccount

        // Kakao가 유효성과 인증 여부를 모두 true로 보장한 이메일만 저장한다.
        val verifiedEmail = account?.email.takeIf {
            account?.isEmailValid == true && account.isEmailVerified == true
        }

        // 최종 로그인 키는 클라이언트 요청값이 아니라 검증된 Kakao 사용자 ID다.
        return SocialIdentity(
            provider = provider,
            subject = userInfo.id.toString(),
            email = verifiedEmail,
        )
    }

    /**
     * Kakao GET API의 공통 호출/오류 변환 함수다.
     * 400/401은 잘못된 토큰, 그 외 응답이나 네트워크 실패는 일시적인 제공자 장애로 구분한다.
     */
    private inline fun <reified T : Any> request(token: String, uri: String): T {
        try {
            return restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(T::class.java)
                ?: throw SocialVerificationUnavailableException(
                    provider,
                    IllegalStateException("Kakao returned an empty response"),
                )
        } catch (exception: RestClientResponseException) {
            if (exception.statusCode.value() == 400 || exception.statusCode.value() == 401) {
                throw InvalidAuthTokenException("Kakao access token is invalid")
            }
            throw SocialVerificationUnavailableException(provider, exception)
        } catch (exception: RestClientException) {
            throw SocialVerificationUnavailableException(provider, exception)
        }
    }
}

/** `/v1/user/access_token_info` 응답 중 검증에 필요한 필드만 받는다. */
private data class KakaoTokenInfoResponse(
    /** 토큰 소유자의 Kakao 숫자 사용자 ID다. */
    val id: Long? = null,
    /** 이 토큰을 발급한 Kakao 애플리케이션 ID다. */
    @param:JsonProperty("app_id")
    val appId: Long? = null,
)

/** `/v2/user/me` 응답 중 사용자 ID와 계정 정보만 받는다. */
private data class KakaoUserInfoResponse(
    /** 사용자 정보 API가 반환한 Kakao 사용자 ID이며 토큰 정보의 ID와 같아야 한다. */
    val id: Long? = null,
    /** 사용자가 제공에 동의한 Kakao 계정 정보이며 없을 수도 있다. */
    @param:JsonProperty("kakao_account")
    val kakaoAccount: KakaoAccountResponse? = null,
)

/** Kakao 이메일의 값과 검증 상태를 함께 받는다. */
private data class KakaoAccountResponse(
    /** 사용자가 제공에 동의한 경우에만 내려오는 이메일 값이다. */
    val email: String? = null,
    /** Kakao가 이메일 형식과 현재 사용 가능 상태를 유효하다고 판단했는지 나타낸다. */
    @param:JsonProperty("is_email_valid")
    val isEmailValid: Boolean? = null,
    /** Kakao 계정에서 실제 이메일 인증을 완료했는지 나타낸다. */
    @param:JsonProperty("is_email_verified")
    val isEmailVerified: Boolean? = null,
)
