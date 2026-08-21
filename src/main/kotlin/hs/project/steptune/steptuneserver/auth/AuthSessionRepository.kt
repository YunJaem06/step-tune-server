package hs.project.steptune.steptuneserver.auth

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/** Refresh Token 세션을 조회·저장하는 Spring Data JPA 저장소다. */
interface AuthSessionRepository : JpaRepository<AuthSessionEntity, UUID> {
    /**
     * 토큰 해시로 세션을 찾으면서 DB 쓰기 잠금을 건다.
     * 두 요청이 같은 Refresh Token을 동시에 회전시키는 상황에서 한 요청만 성공하게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select session
        from AuthSessionEntity session
        join fetch session.user
        where session.refreshTokenHash = :tokenHash
        """,
    )
    fun findByTokenHashForUpdate(@Param("tokenHash") tokenHash: String): AuthSessionEntity?
}
