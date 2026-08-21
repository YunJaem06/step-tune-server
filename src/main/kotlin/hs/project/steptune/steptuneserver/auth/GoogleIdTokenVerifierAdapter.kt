package hs.project.steptune.steptuneserver.auth

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import hs.project.steptune.steptuneserver.config.AuthProperties
import org.springframework.stereotype.Component
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Android 앱에서 받은 Google ID Token을 Google 공개키와 OAuth Client ID 기준으로 검증한다.
 * 클라이언트가 임의로 보낸 Google userId를 신뢰하지 않고, 서명이 확인된 payload의 subject만 사용한다.
 */
@Component
class GoogleIdTokenVerifierAdapter(
    /** Google Web Client ID를 읽어 ID Token의 발급 대상(audience)을 제한하는 서버 설정이다. */
    properties: AuthProperties,
) : SocialTokenVerifier {
    /** 레지스트리가 이 구현체를 GOOGLE 요청에 연결할 때 사용하는 값이다. */
    override val provider = SocialProvider.GOOGLE

    /**
     * audience를 Web Client ID로 제한한다.
     * 따라서 다른 앱을 대상으로 발급된 정상 Google 토큰도 Step Tune 로그인에는 사용할 수 없다.
     */
    private val verifier = GoogleIdTokenVerifier.Builder(
        NetHttpTransport.Builder().build(),
        GsonFactory.getDefaultInstance(),
    )
        .setAudience(listOf(properties.googleClientId))
        .build()

    /** 서명, 발급자, audience, 만료 시간을 검증하고 공통 SocialIdentity로 변환한다. */
    override fun verify(token: String): SocialIdentity {
        val googleToken = try {
            verifier.verify(token)
        } catch (exception: IOException) {
            // Google 키 조회 등 네트워크 문제는 사용자 토큰 오류가 아닌 일시적 외부 장애로 처리한다.
            throw SocialVerificationUnavailableException(provider, exception)
        } catch (exception: GeneralSecurityException) {
            // 서명 알고리즘/키 검증 실패는 인증 실패다.
            throw InvalidAuthTokenException("Google ID token is invalid")
        } catch (exception: IllegalArgumentException) {
            // JWT 문자열 형식 자체가 잘못된 경우도 인증 실패다.
            throw InvalidAuthTokenException("Google ID token is invalid")
        } ?: throw InvalidAuthTokenException("Google ID token is invalid")

        val payload = googleToken.payload

        // 이메일은 Google이 실제 확인했다고 표시한 경우에만 참고 정보로 저장한다.
        val verifiedEmail = payload.email.takeIf { payload.emailVerified == true }

        // subject는 Google 계정의 고유 ID이며 이메일처럼 사용자가 바꿀 수 있는 값을 로그인 키로 쓰지 않는다.
        return SocialIdentity(
            provider = provider,
            subject = payload.subject,
            email = verifiedEmail,
        )
    }
}
