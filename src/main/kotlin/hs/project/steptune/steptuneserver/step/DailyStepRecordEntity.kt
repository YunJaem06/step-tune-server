package hs.project.steptune.steptuneserver.step

import hs.project.steptune.steptuneserver.user.UserEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.time.LocalDate

/**
 * 한 사용자의 하루 총걸음 수를 저장하는 JPA 엔티티다.
 * 앱이 보내는 값은 걸음 증분이 아니라 해당 날짜의 최신 총합이므로 재동기화할 때 값을 교체한다.
 */
@Entity
@Table(
    name = "daily_step_records",
    uniqueConstraints = [
        // 같은 사용자의 같은 날짜는 DB에도 반드시 한 행만 존재하게 한다.
        UniqueConstraint(
            name = "uk_daily_step_records_user_date",
            columnNames = ["user_id", "record_date"],
        ),
    ],
)
class DailyStepRecordEntity(
    /** MySQL이 자동 발급하는 걸음 기록 자체의 내부 식별자다. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    var id: Long? = null,

    /** 기록의 소유자이며 회원 탈퇴 시 DB 외래키 CASCADE로 기록도 함께 삭제된다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: UserEntity,

    /** 휴대폰 사용자의 현지 날짜 기준으로 집계한 날짜다. */
    @Column(name = "record_date", nullable = false)
    var recordDate: LocalDate,

    /** 해당 날짜의 최신 하루 총걸음 수이며 음수는 허용하지 않는다. */
    @Column(name = "step_count", nullable = false)
    var stepCount: Int,

    /** Android가 이 총걸음 값을 마지막으로 측정한 시각을 UTC로 정규화해 저장한다. */
    @Column(name = "measured_at", nullable = false)
    var measuredAt: Instant,

    /** 이 날짜 기록이 서버에 처음 생성된 UTC 시각이다. */
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    /** 같은 날짜의 값까지 포함해 서버 DB에서 마지막으로 수정된 UTC 시각이다. */
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

