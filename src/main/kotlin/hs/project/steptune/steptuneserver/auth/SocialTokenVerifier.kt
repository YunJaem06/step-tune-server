package hs.project.steptune.steptuneserver.auth

import org.springframework.stereotype.Component

/** 제공자 토큰 검증을 통과한 뒤 서버 내부에서 공통으로 사용하는 소셜 신원 형식이다. */
data class SocialIdentity(
    /** 신원을 확인한 소셜 제공자다. */
    val provider: SocialProvider,
    /** 제공자가 보장하는 사용자 고유 ID이며 내부 DB의 providerSubject로 저장한다. */
    val subject: String,
    /** 제공자가 유효성을 확인한 경우에만 들어오는 참고용 이메일이다. */
    val email: String?,
)

/** Google·Kakao·Naver 검증 구현체가 따라야 하는 공통 계약이다. */
interface SocialTokenVerifier {
    /** 이 구현체가 담당하는 제공자다. */
    val provider: SocialProvider

    /** 토큰을 외부 제공자 규칙대로 검증하고 신뢰 가능한 공통 신원으로 변환한다. */
    fun verify(token: String): SocialIdentity
}

/**
 * Spring이 찾아 준 모든 [SocialTokenVerifier]를 제공자별 Map으로 묶는 레지스트리다.
 * AuthService가 when 분기 없이 provider에 맞는 검증기를 선택하게 해준다.
 */
@Component
class SocialTokenVerifierRegistry(
    /** Spring이 Component로 등록한 Google/Kakao/Naver 검증 구현체 전체를 자동 주입한다. */
    verifiers: List<SocialTokenVerifier>,
) {
    /** GOOGLE→Google verifier처럼 한 번 구성해 로그인 요청마다 빠르게 찾는다. */
    private val verifiersByProvider = verifiers.associateBy { it.provider }

    /** 해당 제공자 검증기가 없으면 서버 설정 누락으로 명확하게 실패시킨다. */
    fun verify(provider: SocialProvider, token: String): SocialIdentity {
        val verifier = verifiersByProvider[provider]
            ?: throw SocialProviderNotConfiguredException(provider)
        return verifier.verify(token)
    }
}
