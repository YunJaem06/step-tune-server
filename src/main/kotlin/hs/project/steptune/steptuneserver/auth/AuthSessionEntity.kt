package hs.project.steptune.steptuneserver.auth

import hs.project.steptune.steptuneserver.user.UserEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 한 번 발급한 Refresh Token의 서버 측 세션 상태를 나타낸다.
 *
 * Access Token은 짧게 사용하고 DB에 저장하지 않지만, Refresh Token은 로그아웃·회전·만료를
 * 서버가 통제해야 하므로 이 테이블에 해시와 상태를 기록한다.
 */
@Entity
@Table(name = "auth_sessions")
class AuthSessionEntity(
    /** 세션 레코드 자체의 내부 식별자다. */
    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    /** 이 Refresh Token을 발급받은 Step Tune 사용자다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: UserEntity,

    /** 자동 로그인 후 새 JWT에도 최초 로그인 제공자를 유지하기 위해 저장한다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var provider: SocialProvider,

    /** 원문 Refresh Token을 저장하지 않고 SHA-256 해시만 저장한다. */
    @Column(name = "refresh_token_hash", nullable = false, length = 64)
    var refreshTokenHash: String,

    /** 세션을 처음 만든 UTC 시각이다. */
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    /** 이 시각 이후에는 자동 로그인에 사용할 수 없다. */
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    /** Refresh Token이 실제 회전에 사용된 마지막 시각이다. */
    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null,

    /** 로그아웃 또는 회전으로 폐기된 시각이며, null이면 아직 폐기되지 않았다. */
    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
)
