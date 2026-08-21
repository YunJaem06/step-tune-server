package hs.project.steptune.steptuneserver.user

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/** Step Tune 내부 사용자를 조회·저장하는 Spring Data JPA 저장소다. */
interface UserRepository : JpaRepository<UserEntity, UUID> {
    /** 랜덤 또는 향후 사용자 지정 닉네임의 중복 여부를 확인한다. */
    fun existsByNickname(nickname: String): Boolean
}
