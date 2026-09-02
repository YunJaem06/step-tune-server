package hs.project.steptune.steptuneserver.user

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** Step Tune 내부 사용자를 조회·저장하는 Spring Data JPA 저장소다. */
interface UserRepository : JpaRepository<UserEntity, Long> {
    /**
     * 걸음 동기화 중 같은 사용자의 다른 동기화나 회원 탈퇴가 겹치지 않도록 사용자 행을 잠근다.
     * 트랜잭션이 끝나면 DB가 잠금을 자동 해제한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserEntity user where user.id = :userId")
    fun findByIdForUpdate(@Param("userId") userId: Long): UserEntity?

    /** 랜덤 또는 향후 사용자 지정 닉네임의 중복 여부를 확인한다. */
    fun existsByNickname(nickname: String): Boolean

    /** 현재 사용자를 제외한 다른 사용자가 요청 닉네임을 이미 쓰는지 확인한다. */
    fun existsByNicknameAndIdNot(nickname: String, id: Long): Boolean
}
