package hs.project.steptune.steptuneserver.auth

import hs.project.steptune.steptuneserver.user.UserEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

/** 서버가 지원하는 소셜 로그인 제공자 목록이다. DB에는 이 이름 그대로 문자열로 저장한다. */
enum class SocialProvider {
    /** Google Identity Services에서 받은 ID Token을 검증하는 로그인이다. */
    GOOGLE,
    /** Kakao SDK에서 받은 Access Token을 Kakao API로 재검증하는 로그인이다. */
    KAKAO,
    /** Naver SDK에서 받은 Access Token을 Naver 프로필 API로 검증하는 로그인이다. */
    NAVER,
}

/**
 * 외부 소셜 계정과 Step Tune 내부 사용자를 연결하는 엔티티다.
 *
 * 앱이 전달한 providerId를 믿지 않고, 검증된 토큰에서 얻은 providerSubject만 저장한다.
 * `(provider, providerSubject)` 조합이 같으면 재설치 후에도 동일한 내부 사용자를 찾을 수 있다.
 */
@Entity
@Table(name = "social_accounts")
class SocialAccountEntity(
    /** MySQL이 1부터 순서대로 생성하는 연결 레코드의 내부 식별자다. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    var id: Long? = null,

    /** 이 소셜 계정이 연결된 Step Tune 사용자다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: UserEntity,

    /** GOOGLE, KAKAO, NAVER 중 하나다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var provider: SocialProvider,

    /** 각 제공자가 토큰 검증 결과로 돌려준 변경되지 않는 사용자 고유 ID다. */
    @Column(name = "provider_subject", nullable = false, length = 255)
    var providerSubject: String,

    /** 제공자가 검증해 준 경우에만 저장하는 참고용 이메일이며 로그인 키로 사용하지 않는다. */
    @Column(length = 320)
    var email: String? = null,

    /** 이 소셜 계정을 처음 연결한 UTC 시각이다. */
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
