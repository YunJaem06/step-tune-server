package hs.project.steptune.steptuneserver.auth

import hs.project.steptune.steptuneserver.config.AuthProperties
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Kakao/Naver 검증기가 실제로 보낼 URL·헤더와 응답 매핑 규칙을 가짜 HTTP 서버로 검증한다. */
class SocialProviderVerifierTests {
    /** Kakao 요청 헤더, app_id 일치 검사, 사용자 ID와 검증 이메일 매핑을 검증한다. */
    @Test
    fun `Kakao verifier checks app id and maps verified profile`() {
        // RestClient 앞에 Mock 서버를 연결해 외부 인터넷 없이 Kakao 호출 계약을 검증한다.
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val verifier = KakaoAccessTokenVerifier(properties(kakaoAppId = 1234L), builder)
        server.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer kakao-token"))
            .andRespond(
                withSuccess(
                    """{"id":123456789,"expires_in":7199,"app_id":1234}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me?secure_resource=true"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer kakao-token"))
            .andRespond(
                withSuccess(
                    """
                    {
                      "id": 123456789,
                      "kakao_account": {
                        "email": "runner@kakao.com",
                        "is_email_valid": true,
                        "is_email_verified": true,
                        "profile": {
                          "nickname": "Kakao Runner",
                          "profile_image_url": "https://example.com/kakao.png"
                        }
                      }
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val identity = verifier.verify("kakao-token")

        assertEquals(SocialProvider.KAKAO, identity.provider)
        assertEquals("123456789", identity.subject)
        assertEquals("runner@kakao.com", identity.email)
        server.verify()
    }

    /** 다른 Kakao 애플리케이션에서 발급한 정상 토큰도 Step Tune에서는 거절하는지 검증한다. */
    @Test
    fun `Kakao verifier rejects a token issued for another app`() {
        // 유효한 Kakao 토큰이어도 app_id가 Step Tune 설정과 다르면 거절해야 한다.
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val verifier = KakaoAccessTokenVerifier(properties(kakaoAppId = 1234L), builder)
        server.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
            .andRespond(
                withSuccess(
                    """{"id":123456789,"expires_in":7199,"app_id":9999}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertFailsWith<InvalidAuthTokenException> {
            verifier.verify("other-app-token")
        }
        server.verify()
    }

    /** Naver 요청에 서버 자격 증명이 포함되고 응답 프로필이 공통 신원으로 매핑되는지 검증한다. */
    @Test
    fun `Naver verifier sends app credentials and maps profile`() {
        // Authorization과 서버 전용 Client ID/Secret 헤더가 모두 포함되는지 확인한다.
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val verifier = NaverAccessTokenVerifier(
            properties(
                naverClientId = "naver-client-id",
                naverClientSecret = "naver-client-secret",
            ),
            builder,
        )
        server.expect(requestTo("https://openapi.naver.com/v1/nid/me"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer naver-token"))
            .andExpect(header("X-Naver-Client-Id", "naver-client-id"))
            .andExpect(header("X-Naver-Client-Secret", "naver-client-secret"))
            .andRespond(
                withSuccess(
                    """
                    {
                      "resultcode": "00",
                      "message": "success",
                      "response": {
                        "id": "naver-subject-123",
                        "email": "runner@naver.com",
                        "nickname": "Naver Runner",
                        "name": "Runner",
                        "profile_image": "https://example.com/naver.png"
                      }
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val identity = verifier.verify("naver-token")

        assertEquals(SocialProvider.NAVER, identity.provider)
        assertEquals("naver-subject-123", identity.subject)
        assertEquals("runner@naver.com", identity.email)
        server.verify()
    }

    /** 각 제공자 검증기에 필요한 설정만 테스트별로 바꿔 주는 공통 설정 생성 함수다. */
    private fun properties(
        kakaoAppId: Long? = null,
        naverClientId: String? = null,
        naverClientSecret: String? = null,
    ) = AuthProperties(
        googleClientId = "test.apps.googleusercontent.com",
        kakaoAppId = kakaoAppId,
        naverClientId = naverClientId,
        naverClientSecret = naverClientSecret,
        jwtSecret = "0123456789abcdef0123456789abcdef",
    )
}
