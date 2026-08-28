package hs.project.steptune.steptuneserver.auth

import hs.project.steptune.steptuneserver.user.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** AuthService의 사용자/소셜 계정/세션 DB 처리와 JWT 발급 규칙을 함께 검증한다. */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:step_tune_auth;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.auth.google-client-id=test.apps.googleusercontent.com",
        "app.auth.jwt-secret=0123456789abcdef0123456789abcdef",
    ],
)
class AuthServiceIntegrationTests {
    /** 외부 네트워크 대신 검증 완료 신원을 반환하도록 소셜 검증 레지스트리만 대체한다. */
    @MockitoBean
    lateinit var socialTokenVerifierRegistry: SocialTokenVerifierRegistry

    /** 테스트 대상인 실제 인증 업무 Service다. */
    @Autowired
    lateinit var authService: AuthService

    /** 발급한 JWT의 서명과 claim을 운영 코드와 같은 규칙으로 다시 검증한다. */
    @Autowired
    lateinit var jwtDecoder: JwtDecoder

    /** 사용자 생성·재사용 결과를 DB에서 확인하고 테스트 데이터를 정리한다. */
    @Autowired
    lateinit var userRepository: UserRepository

    /** 제공자별 외부 계정 연결 결과를 확인하고 테스트 데이터를 정리한다. */
    @Autowired
    lateinit var socialAccountRepository: SocialAccountRepository

    /** Refresh 세션 생성·회전·폐기 결과를 확인하고 테스트 데이터를 정리한다. */
    @Autowired
    lateinit var authSessionRepository: AuthSessionRepository

    /** 테스트마다 DB를 비워 실행 순서와 무관한 상태로 만든다. */
    @BeforeEach
    fun cleanDatabase() {
        authSessionRepository.deleteAll()
        socialAccountRepository.deleteAll()
        userRepository.deleteAll()
    }

    /** 최초 Google 로그인에서 사용자·소셜 연결·세션과 올바른 JWT가 함께 만들어지는지 검증한다. */
    @Test
    fun `google login creates one user and returns valid Step Tune tokens`() {
        // Given: 유효한 Google 토큰 검증 결과를 준비한다.
        givenGoogleIdentity()

        // When: 최초 Google 로그인을 실행하고 발급 JWT를 실제 디코더로 검증한다.
        val response = authService.loginWithGoogle(GoogleLoginRequest(idToken = "google-id-token"))
        val jwt = jwtDecoder.decode(response.accessToken)

        // Then: claim, 랜덤 닉네임, 세 DB 레코드가 설계와 일치해야 한다.
        assertEquals(response.userData.userId.toString(), jwt.subject)
        assertEquals("https://step-tune.local", jwt.issuer.toString())
        assertEquals(listOf("step-tune-android"), jwt.audience)
        assertEquals("access", jwt.getClaimAsString("token_use"))
        assertEquals("GOOGLE", jwt.getClaimAsString("auth_provider"))
        assert(response.userData.userId > 0)
        assert(response.userData.nickName.matches(Regex("스텝러너\\d{8}")))
        assertEquals(1, userRepository.count())
        assertEquals(1, socialAccountRepository.count())
        assertEquals(1, authSessionRepository.count())
    }

    /** 같은 Google subject로 반복 로그인해도 내부 사용자가 중복 생성되지 않는지 검증한다. */
    @Test
    fun `same Google subject reuses the same Step Tune user`() {
        givenGoogleIdentity()

        // 서로 다른 토큰이어도 검증된 provider+subject가 같으면 같은 사용자여야 한다.
        val first = authService.loginWithGoogle(GoogleLoginRequest("first-google-token"))
        val second = authService.loginWithGoogle(GoogleLoginRequest("second-google-token"))

        assertEquals(first.userData.userId, second.userData.userId)
        assertEquals(first.userData.nickName, second.userData.nickName)
        assertEquals(1, userRepository.count())
        assertEquals(1, socialAccountRepository.count())
        assertEquals(2, authSessionRepository.count())
    }

    /** 이메일만 같은 서로 다른 제공자 계정을 위험하게 자동 병합하지 않는지 검증한다. */
    @Test
    fun `different social providers do not automatically merge accounts`() {
        givenGoogleIdentity()
        given(socialTokenVerifierRegistry.verify(SocialProvider.KAKAO, "kakao-token")).willReturn(
            SocialIdentity(
                provider = SocialProvider.KAKAO,
                subject = "kakao-subject-123",
                email = "runner@example.com",
            ),
        )

        // 이메일이 같아도 제공자가 다르면 안전을 위해 자동 병합하지 않는다.
        val google = authService.loginWithGoogle(GoogleLoginRequest("google-token"))
        val kakao = authService.loginWithSocial(
            SocialLoginRequest(SocialProvider.KAKAO, "kakao-token"),
        )

        assertNotEquals(google.userData.userId, kakao.userData.userId)
        // 같은 테스트에서 연속 생성한 두 사용자는 DB의 다음 숫자 ID를 차례대로 받아야 한다.
        assertEquals(google.userData.userId + 1, kakao.userData.userId)
        assertEquals(2, userRepository.count())
        assertEquals(2, socialAccountRepository.count())
    }

    /** Refresh Token을 한 번 사용하면 새 토큰으로 교체되고 이전 토큰은 거절되는지 검증한다. */
    @Test
    fun `refresh rotates token and rejects the old token`() {
        givenGoogleIdentity()
        val login = authService.loginWithGoogle(GoogleLoginRequest("google-id-token"))

        // 한 번 사용한 Refresh Token은 새 토큰으로 회전된 직후 재사용할 수 없어야 한다.
        val refreshed = authService.refresh(RefreshRequest(login.refreshToken))

        assertNotEquals(login.refreshToken, refreshed.refreshToken)
        assertFailsWith<InvalidAuthTokenException> {
            authService.refresh(RefreshRequest(login.refreshToken))
        }
    }

    /** 로그아웃이 Refresh 세션을 폐기해 이후 자동 로그인을 막는지 검증한다. */
    @Test
    fun `logout revokes refresh token`() {
        givenGoogleIdentity()
        val login = authService.loginWithGoogle(GoogleLoginRequest("google-id-token"))

        // 로그아웃은 서버 세션을 폐기하여 앱에 남은 토큰으로 재발급하지 못하게 한다.
        authService.logout(LogoutRequest(login.refreshToken))

        assertFailsWith<InvalidAuthTokenException> {
            authService.refresh(RefreshRequest(login.refreshToken))
        }
    }

    /** 여러 테스트 토큰이 동일한 검증 완료 Google 계정을 가리키도록 만드는 공통 준비 함수다. */
    private fun givenGoogleIdentity() {
        val identity = SocialIdentity(
            provider = SocialProvider.GOOGLE,
            subject = "google-subject-123",
            email = "runner@example.com",
        )
        listOf(
            "google-id-token",
            "first-google-token",
            "second-google-token",
            "google-token",
        ).forEach { token ->
            given(socialTokenVerifierRegistry.verify(SocialProvider.GOOGLE, token))
                .willReturn(identity)
        }
    }
}
