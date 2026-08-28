package hs.project.steptune.steptuneserver.user

import org.springframework.data.jpa.repository.JpaRepository

/** Step Tune 내부 사용자를 조회·저장하는 Spring Data JPA 저장소다. */
interface UserRepository : JpaRepository<UserEntity, Long> {
    /** 랜덤 또는 향후 사용자 지정 닉네임의 중복 여부를 확인한다. */
    fun existsByNickname(nickname: String): Boolean

    /** 현재 사용자를 제외한 다른 사용자가 요청 닉네임을 이미 쓰는지 확인한다. */
    fun existsByNicknameAndIdNot(nickname: String, id: Long): Boolean
}
