package hs.project.steptune.steptuneserver.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Step Tune 기능 전체에서 기준이 되는 내부 사용자 엔티티다.
 * 소셜 제공자별 ID 대신 이 숫자 ID를 운동 기록, 설정 등 다른 도메인의 외래 키로 사용한다.
 */
@Entity
@Table(name = "app_users")
class UserEntity(
    /** MySQL이 1부터 순서대로 생성하는 내부 사용자 고유 ID다. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    var id: Long? = null,

    /** 앱에 표시되는 중복 불가 닉네임이다. 최초 로그인 시 랜덤 생성한다. */
    @Column(nullable = false, unique = true, length = 30)
    var nickname: String,

    /** 사용자가 처음 만들어진 UTC 시각이다. */
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    /** 정상 소셜 로그인이 마지막으로 완료된 UTC 시각이다. */
    @Column(name = "last_login_at", nullable = false)
    var lastLoginAt: Instant = Instant.now(),
)
