package hs.project.steptune.steptuneserver.user

import hs.project.steptune.steptuneserver.auth.UserNotFoundException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 현재 사용자의 조회, 닉네임 변경, 회원 탈퇴 규칙을 담당한다.
 * Controller는 인증된 userId와 요청값만 전달하고 실제 DB 처리는 이 Service에 모은다.
 */
@Service
class UserService(
    /** 사용자 조회·수정·삭제와 닉네임 중복 확인을 실행하는 JPA 저장소다. */
    private val userRepository: UserRepository,
) {
    /** JWT subject에서 얻은 내부 userId로 최신 프로필을 조회한다. */
    @Transactional(readOnly = true)
    fun getUser(userId: Long): UserData = findUser(userId).toData()

    /**
     * 현재 사용자를 제외하고 요청 닉네임을 다른 사용자가 쓰는지 확인한다.
     * 이 결과는 화면 안내용이며, 동시 요청이 있을 수 있으므로 실제 변경에서도 다시 검사한다.
     */
    @Transactional(readOnly = true)
    fun checkNicknameAvailability(userId: Long, requestedNickname: String): NicknameAvailabilityData {
        // 탈퇴한 사용자의 유효기간이 남은 Access Token으로 중복 확인하는 요청도 거절한다.
        findUser(userId)
        val nickname = normalizeNickname(requestedNickname)
        val available = !userRepository.existsByNicknameAndIdNot(nickname, userId)
        return NicknameAvailabilityData(nickName = nickname, available = available)
    }

    /** 닉네임을 정규화하고 중복을 검사한 뒤 현재 사용자의 프로필을 변경한다. */
    @Transactional
    fun updateNickname(userId: Long, request: UpdateNicknameRequest): UserData {
        val user = findUser(userId)
        val nickname = normalizeNickname(request.nickName)

        // 자신의 현재 닉네임은 그대로 저장할 수 있지만 다른 사용자의 닉네임은 사용할 수 없다.
        if (userRepository.existsByNicknameAndIdNot(nickname, userId)) {
            throw NicknameAlreadyExistsException()
        }

        user.nickname = nickname
        try {
            // 중복 확인 직후 다른 요청이 같은 닉네임을 선점하는 경우도 DB UNIQUE 제약으로 막는다.
            userRepository.flush()
        } catch (_: DataIntegrityViolationException) {
            throw NicknameAlreadyExistsException()
        }
        return user.toData()
    }

    /**
     * 현재 사용자 레코드를 삭제한다.
     * DB의 ON DELETE CASCADE가 연결된 소셜 계정과 모든 Refresh Token 세션도 함께 삭제한다.
     */
    @Transactional
    fun deleteUser(userId: Long) {
        val user = findUser(userId)
        userRepository.delete(user)
        // 요청이 성공하기 전에 실제 DELETE와 외래키 CASCADE까지 DB에 반영됐는지 확인한다.
        userRepository.flush()
    }

    /** 모든 프로필 기능이 동일하게 404를 반환하도록 사용자 조회 규칙을 한곳에 둔다. */
    private fun findUser(userId: Long): UserEntity =
        userRepository.findById(userId).orElseThrow { UserNotFoundException() }

    /** 앞뒤 공백을 제거하고 DB의 VARCHAR(30) 제약에 맞는지 검사한다. */
    private fun normalizeNickname(requestedNickname: String): String {
        val nickname = requestedNickname.trim()
        if (nickname.isEmpty() || nickname.length > MAX_NICKNAME_LENGTH) {
            throw InvalidNicknameException()
        }
        return nickname
    }

    /** JPA 엔티티에서 외부에 노출할 최소 사용자 응답만 만든다. */
    private fun UserEntity.toData(): UserData = UserData(
        userId = requireNotNull(id) { "Persisted user must have an id" },
        nickName = nickname,
    )

    private companion object {
        /** app_users.nickname VARCHAR(30)과 동일하게 유지하는 최대 길이다. */
        const val MAX_NICKNAME_LENGTH = 30
    }
}
