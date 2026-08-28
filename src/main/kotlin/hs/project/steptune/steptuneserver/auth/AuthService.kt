package hs.project.steptune.steptuneserver.auth

import hs.project.steptune.steptuneserver.config.AuthProperties
import hs.project.steptune.steptuneserver.user.UserData
import hs.project.steptune.steptuneserver.user.UserEntity
import hs.project.steptune.steptuneserver.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 소셜 로그인부터 Step Tune 토큰 발급까지 인증 업무 흐름을 담당한다.
 *
 * 외부 토큰을 직접 신뢰하지 않고 제공자별 검증기로 확인한 후,
 * 검증 결과의 고유 subject를 DB 계정과 연결한다. Controller에는 HTTP 처리만 남기고
 * 사용자 생성, 세션 회전, 로그아웃 같은 업무 규칙은 이 Service에 모은다.
 */
@Service
class AuthService(
    /** provider 값에 맞는 Google/Kakao/Naver 검증기를 찾아 외부 토큰을 검증한다. */
    private val socialTokenVerifierRegistry: SocialTokenVerifierRegistry,
    /** Step Tune 내부 사용자 레코드를 조회하고 저장한다. */
    private val userRepository: UserRepository,
    /** 검증된 소셜 계정과 내부 사용자의 연결을 조회하고 저장한다. */
    private val socialAccountRepository: SocialAccountRepository,
    /** Refresh Token의 만료·회전·로그아웃 상태를 조회하고 저장한다. */
    private val authSessionRepository: AuthSessionRepository,
    /** Step Tune 자체 Access/Refresh Token 생성과 해시 계산을 담당한다. */
    private val tokenService: TokenService,
    /** 토큰 유효기간 등 application.properties의 인증 설정값이다. */
    private val properties: AuthProperties,
) {
    /** 토큰 만료와 로그인 시간을 모두 UTC 기준으로 기록한다. */
    private val clock: Clock = Clock.systemUTC()

    /** 추측하기 어려운 초기 랜덤 닉네임을 만들 때 사용하는 난수 생성기다. */
    private val secureRandom = SecureRandom()

    /** Google 전용 요청을 공통 로그인 흐름으로 변환한다. */
    @Transactional
    fun loginWithGoogle(request: GoogleLoginRequest): AuthData {
        // 호환용 Google 전용 API도 내부에서는 공통 provider+token 로그인 함수로 합친다.
        return login(SocialProvider.GOOGLE, request.idToken)
    }

    /** 요청의 provider에 맞는 검증기를 골라 공통 로그인 흐름을 실행한다. */
    @Transactional
    fun loginWithSocial(request: SocialLoginRequest): AuthData {
        // 공통 API는 요청 provider를 그대로 사용해 해당 소셜 검증기로 라우팅한다.
        return login(request.provider, request.token)
    }

    /**
     * 로그인과 최초 자동 회원가입을 함께 처리한다.
     * 기존 `(provider, subject)`가 있으면 같은 사용자를 재사용하고, 없으면 신규 사용자를 만든다.
     */
    private fun login(provider: SocialProvider, token: String): AuthData {
        // 1. Google/Kakao/Naver가 발급한 토큰을 제공자 방식에 맞게 검증한다.
        val identity = socialTokenVerifierRegistry.verify(provider, token)

        // 검증기가 잘못된 제공자의 신원을 반환하는 구현 실수까지 방어한다.
        if (identity.provider != provider) {
            throw InvalidAuthTokenException("Social token provider does not match")
        }

        val now = Instant.now(clock)

        // 2. 검증된 외부 고유 ID로 기존 계정을 찾고, 처음 보는 계정만 자동 생성한다.
        val account = socialAccountRepository.findByProviderAndProviderSubject(
            provider,
            identity.subject,
        ) ?: createSocialAccount(identity, now)

        // 3. 제공자가 검증해 준 최신 이메일과 마지막 로그인 시각을 갱신한다.
        account.email = identity.email
        account.user.lastLoginAt = now

        // 4. 이후 Step Tune API에서 사용할 자체 Access/Refresh Token을 발급한다.
        return issueTokens(account, now)
    }

    /**
     * Refresh Token으로 자동 로그인한다.
     * 같은 Refresh Token의 동시 재사용을 막기 위해 DB 행 잠금과 토큰 회전을 함께 사용한다.
     */
    @Transactional
    fun refresh(request: RefreshRequest): AuthData {
        // DB에는 원문이 없으므로 요청 토큰도 같은 SHA-256 방식으로 해시한 뒤 조회한다.
        val tokenHash = tokenService.hashRefreshToken(request.refreshToken)
        val session = authSessionRepository.findByTokenHashForUpdate(tokenHash)
            ?: throw InvalidAuthTokenException("Refresh token is invalid")
        val now = Instant.now(clock)

        // 이미 로그아웃/회전에 사용됐거나 유효기간이 지난 세션은 거절한다.
        if (session.revokedAt != null || !session.expiresAt.isAfter(now)) {
            throw InvalidAuthTokenException("Refresh token is expired or revoked")
        }

        // 요청에 사용한 토큰은 즉시 폐기한다. 이후 issueTokens가 새 토큰과 새 세션을 만든다.
        session.lastUsedAt = now
        session.revokedAt = now

        // 최초 로그인 제공자를 유지하여 새 JWT에도 동일한 auth_provider를 기록한다.
        val account = socialAccountRepository.findByUserIdAndProvider(
            session.user.requireId(),
            session.provider,
        ) ?: throw UserNotFoundException()

        return issueTokens(account, now)
    }

    /**
     * 전달받은 Refresh Token의 세션을 폐기한다.
     * 존재하지 않거나 이미 폐기된 토큰에도 성공 응답을 주어 토큰 존재 여부가 노출되지 않게 한다.
     */
    @Transactional
    fun logout(request: LogoutRequest) {
        // 원문을 저장하지 않으므로 요청값을 동일하게 해시해야 DB 세션을 찾을 수 있다.
        val tokenHash = tokenService.hashRefreshToken(request.refreshToken)
        // 없는 토큰도 성공 처리하여 공격자가 세션 존재 여부를 추측하지 못하게 한다.
        val session = authSessionRepository.findByTokenHashForUpdate(tokenHash) ?: return
        if (session.revokedAt == null) {
            // 실제 행을 삭제하지 않고 폐기 시각을 남겨 감사와 재사용 차단 상태를 표현한다.
            session.revokedAt = Instant.now(clock)
        }
    }

    /** 검증된 소셜 신원을 Step Tune 신규 사용자 및 소셜 계정으로 한 트랜잭션 안에서 저장한다. */
    private fun createSocialAccount(identity: SocialIdentity, now: Instant): SocialAccountEntity {
        // 앱 사용자의 중심 레코드다. 외부 제공자가 달라도 내부 기능은 이 userId를 기준으로 연결한다.
        val user = userRepository.save(
            UserEntity(
                nickname = generateUniqueNickname(),
                createdAt = now,
                lastLoginAt = now,
            ),
        )

        // provider와 providerSubject 조합이 외부 소셜 계정의 고유 로그인 키가 된다.
        return socialAccountRepository.save(
            SocialAccountEntity(
                user = user,
                provider = identity.provider,
                providerSubject = identity.subject,
                email = identity.email,
                createdAt = now,
            ),
        )
    }

    /** Access Token과 Refresh Token을 만들고 Refresh Token 세션의 해시를 DB에 저장한다. */
    private fun issueTokens(
        account: SocialAccountEntity,
        now: Instant,
    ): AuthData {
        val user = account.user

        // Access Token은 서버 서명이 포함된 JWT이므로 DB 조회 없이도 일반 API 인증에 사용할 수 있다.
        val accessToken = tokenService.createAccessToken(user.requireId(), account.provider)

        // Refresh Token은 임의 문자열이고, 원문은 앱에만 전달한다.
        val refreshToken = tokenService.createRefreshToken()

        // 유출 피해를 줄이기 위해 DB에는 원문 대신 SHA-256 해시와 만료/폐기 상태만 보관한다.
        authSessionRepository.save(
            AuthSessionEntity(
                user = user,
                provider = account.provider,
                refreshTokenHash = refreshToken.hash,
                createdAt = now,
                expiresAt = now.plus(properties.refreshTokenTtl),
            ),
        )

        // 앱은 expiresIn으로 갱신 시점을 판단하고 userData로 로그인 직후 화면을 구성한다.
        return AuthData(
            accessToken = accessToken.value,
            accessTokenExpiresIn = ChronoUnit.SECONDS.between(now, accessToken.expiresAt),
            refreshToken = refreshToken.value,
            userData = user.toData(),
        )
    }

    /**
     * `스텝러너` + 8자리 숫자 형식의 초기 닉네임을 만든다.
     * DB 조회로 기존 닉네임과 겹치지 않는지 확인하고, 충돌 시 최대 10번 다시 생성한다.
     */
    private fun generateUniqueNickname(): String {
        repeat(10) {
            // 0도 앞에 채워 항상 `스텝러너` 뒤에 정확히 8자리 숫자가 붙게 한다.
            val candidate = "스텝러너${secureRandom.nextInt(100_000_000).toString().padStart(8, '0')}"
            if (!userRepository.existsByNickname(candidate)) {
                return candidate
            }
        }
        // 매우 낮은 확률로 10번 모두 충돌하면 잘못된 중복값을 저장하지 않고 트랜잭션을 실패시킨다.
        throw IllegalStateException("Unable to generate a unique nickname")
    }

    /** JPA UserEntity를 외부 API에 노출해도 되는 최소 UserData로 변환한다. */
    private fun UserEntity.toData() = UserData(
        userId = requireId(),
        nickName = nickname,
    )

    /** DB에 저장된 엔티티에 숫자 ID가 없으면 조용히 잘못된 토큰을 만들지 않고 즉시 실패시킨다. */
    private fun UserEntity.requireId(): Long =
        requireNotNull(id) { "Persisted user must have an id" }
}
