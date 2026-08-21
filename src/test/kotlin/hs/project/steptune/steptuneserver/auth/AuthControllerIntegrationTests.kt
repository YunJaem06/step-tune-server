package hs.project.steptune.steptuneserver.auth

import hs.project.steptune.steptuneserver.user.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertNotEquals

/** 실제 HTTP JSON 계약, 인증 필터, Service/DB 연결을 MockMvc로 검증한다. */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:step_tune_http;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.auth.google-client-id=test.apps.googleusercontent.com",
        "app.auth.jwt-secret=0123456789abcdef0123456789abcdef",
    ],
)
@AutoConfigureMockMvc
class AuthControllerIntegrationTests {
    /** 외부 소셜 서버 호출만 가짜 검증 결과로 바꾸고 나머지 서버 계층은 실제로 실행한다. */
    @MockitoBean
    lateinit var socialTokenVerifierRegistry: SocialTokenVerifierRegistry

    /** HTTP 요청을 실제 네트워크 없이 Spring MVC 필터 체인에 전달하는 테스트 클라이언트다. */
    @Autowired
    lateinit var mockMvc: MockMvc

    /** 응답 JSON에서 토큰과 사용자 값을 읽고 요청 DTO를 JSON으로 만드는 변환기다. */
    @Autowired
    lateinit var objectMapper: ObjectMapper

    /** HTTP 요청 결과가 실제 사용자 테이블에 반영됐는지 확인하는 저장소다. */
    @Autowired
    lateinit var userRepository: UserRepository

    /** HTTP 요청 결과가 실제 소셜 연결 테이블에 반영됐는지 확인하는 저장소다. */
    @Autowired
    lateinit var socialAccountRepository: SocialAccountRepository

    /** HTTP 요청 결과가 실제 Refresh 세션 테이블에 반영됐는지 확인하는 저장소다. */
    @Autowired
    lateinit var authSessionRepository: AuthSessionRepository

    /** 각 HTTP 테스트 전에 사용자와 인증 데이터를 초기화한다. */
    @BeforeEach
    fun cleanDatabase() {
        authSessionRepository.deleteAll()
        socialAccountRepository.deleteAll()
        userRepository.deleteAll()
    }

    /** 로그인 응답의 JWT가 실제 보호 API `/me`를 통과하는 전체 HTTP 흐름을 검증한다. */
    @Test
    fun `google login endpoint returns tokens that authorize me endpoint`() {
        // 로그인 응답의 Access Token을 다시 /me에 보내 실제 인증이 연결되는지 확인한다.
        given(socialTokenVerifierRegistry.verify(SocialProvider.GOOGLE, "google-id-token")).willReturn(
            SocialIdentity(
                provider = SocialProvider.GOOGLE,
                subject = "google-subject-http",
                email = "runner@example.com",
            ),
        )

        val loginResult = mockMvc.post("/api/v1/auth/google") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"idToken":"google-id-token"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.code") { value(200) }
            jsonPath("$.message") { value("success") }
            jsonPath("$.data.accessToken") { isNotEmpty() }
            jsonPath("$.data.refreshToken") { isNotEmpty() }
            jsonPath("$.data.accessTokenExpiresIn") { value(900) }
            jsonPath("$.data.userData.userId") { isNotEmpty() }
            jsonPath("$.data.userData.nickName") { isNotEmpty() }
            jsonPath("$.data.userData.profileImageUrl") { doesNotExist() }
        }.andReturn()
        val login = objectMapper.readTree(loginResult.response.contentAsString).get("data")
        val accessToken = login.get("accessToken").stringValue()
        val userId = login.get("userData").get("userId").stringValue()
        val nickName = login.get("userData").get("nickName").stringValue()

        mockMvc.get("/api/v1/me") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.code") { value(200) }
            jsonPath("$.data.userId") { value(userId) }
            jsonPath("$.data.nickName") { value(nickName) }
        }
    }

    /** Refresh Token 회전, 자동 로그인, 로그아웃 후 재사용 차단을 하나의 사용자 흐름으로 검증한다. */
    @Test
    fun `refresh endpoint supports automatic login and logout revokes the rotated token`() {
        // 앱 시작 자동 로그인 시나리오: 로그인 → 갱신 → 로그아웃 → 폐기 토큰 거절을 한 번에 검증한다.
        given(socialTokenVerifierRegistry.verify(SocialProvider.GOOGLE, "auto-login-token")).willReturn(
            SocialIdentity(
                provider = SocialProvider.GOOGLE,
                subject = "google-auto-login-subject",
                email = "auto@example.com",
            ),
        )
        val loginResult = mockMvc.post("/api/v1/auth/social") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"provider":"google","token":"auto-login-token"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn()
        val loginData = objectMapper.readTree(loginResult.response.contentAsString).get("data")
        val oldRefreshToken = loginData.get("refreshToken").stringValue()
        val userId = loginData.get("userData").get("userId").stringValue()

        val refreshResult = mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(RefreshRequest(oldRefreshToken))
        }.andExpect {
            status { isOk() }
            jsonPath("$.code") { value(200) }
            jsonPath("$.data.accessToken") { isNotEmpty() }
            jsonPath("$.data.refreshToken") { isNotEmpty() }
            jsonPath("$.data.userData.userId") { value(userId) }
        }.andReturn()
        val newRefreshToken = objectMapper.readTree(refreshResult.response.contentAsString)
            .get("data")
            .get("refreshToken")
            .stringValue()
        assertNotEquals(oldRefreshToken, newRefreshToken)

        mockMvc.post("/api/v1/auth/logout") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(LogoutRequest(newRefreshToken))
        }.andExpect {
            status { isOk() }
            jsonPath("$.code") { value(200) }
            jsonPath("$.message") { value("success") }
            jsonPath("$.data") { doesNotExist() }
        }

        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(RefreshRequest(newRefreshToken))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value(401) }
        }
    }

    /** 공통 소셜 API가 provider에 따라 Kakao 검증 결과도 동일한 응답 형식으로 처리하는지 검증한다. */
    @Test
    fun `generic social endpoint supports Kakao access token`() {
        // provider를 소문자로 보내도 Kakao 검증기로 라우팅되는 API 계약을 확인한다.
        given(socialTokenVerifierRegistry.verify(SocialProvider.KAKAO, "kakao-access-token")).willReturn(
            SocialIdentity(
                provider = SocialProvider.KAKAO,
                subject = "123456789",
                email = "runner@kakao.com",
            ),
        )

        mockMvc.post("/api/v1/auth/social") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "provider": "kakao",
                  "token": "kakao-access-token"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.code") { value(200) }
            jsonPath("$.data.userData.nickName") { isNotEmpty() }
            jsonPath("$.data.accessToken") { isNotEmpty() }
        }
    }

    /** Access Token이 없는 보호 API 요청이 Controller 전에 Spring Security에서 차단되는지 검증한다. */
    @Test
    fun `me endpoint rejects a request without access token`() {
        // 보호 API는 Authorization 헤더가 없으면 공통 401 JSON을 반환해야 한다.
        mockMvc.get("/api/v1/me")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value(401) }
                jsonPath("$.data") { doesNotExist() }
            }
    }

    /** 외부 토큰 검증 실패가 공통 401 JSON으로 변환되는지 검증한다. */
    @Test
    fun `invalid google token returns unauthorized error`() {
        // 소셜 토큰 검증 실패가 내부 오류 500이 아닌 인증 오류 401로 변환되는지 확인한다.
        given(socialTokenVerifierRegistry.verify(SocialProvider.GOOGLE, "invalid-token"))
            .willThrow(InvalidAuthTokenException("Google ID token is invalid"))

        mockMvc.post("/api/v1/auth/google") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"idToken":"invalid-token"}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value(401) }
            jsonPath("$.data") { doesNotExist() }
        }
    }
}
