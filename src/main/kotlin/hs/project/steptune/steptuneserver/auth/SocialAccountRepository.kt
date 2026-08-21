package hs.project.steptune.steptuneserver.auth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/** 외부 소셜 계정과 내부 사용자의 연결 정보를 조회·저장한다. */
interface SocialAccountRepository : JpaRepository<SocialAccountEntity, UUID> {
    /**
     * 로그인 시 검증된 제공자와 외부 고유 ID로 계정을 찾는다.
     * join fetch로 사용자도 한 쿼리에 가져와 이후 지연 로딩 쿼리를 줄인다.
     */
    @Query(
        """
        select account
        from SocialAccountEntity account
        join fetch account.user
        where account.provider = :provider
          and account.providerSubject = :providerSubject
        """,
    )
    fun findByProviderAndProviderSubject(
        @Param("provider") provider: SocialProvider,
        @Param("providerSubject") providerSubject: String,
    ): SocialAccountEntity?

    /** Refresh Token 세션의 사용자와 제공자로 원래 로그인 계정을 다시 찾는다. */
    @Query(
        """
        select account
        from SocialAccountEntity account
        where account.user.id = :userId
          and account.provider = :provider
        """,
    )
    fun findByUserIdAndProvider(
        @Param("userId") userId: UUID,
        @Param("provider") provider: SocialProvider,
    ): SocialAccountEntity?
}
