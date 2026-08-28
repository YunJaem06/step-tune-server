package hs.project.steptune.steptuneserver.user

import jakarta.validation.constraints.NotBlank

/** 앱 화면과 로컬 캐시에 필요한 최소 사용자 정보다. */
data class UserData(
    /** MySQL이 가입 순서대로 발급한 Step Tune 내부 사용자 식별자다. */
    val userId: Long,
    /** 최초 로그인 때 자동 생성되고 프로필에서 변경할 수 있는 표시 닉네임이다. */
    val nickName: String,
)

/** 현재 사용자의 닉네임을 변경할 때 Android가 보내는 요청이다. */
data class UpdateNicknameRequest(
    /** 공백만 있는 값은 Controller에서 거절하고, 앞뒤 공백 제거와 최대 길이는 Service에서 검사한다. */
    @field:NotBlank
    val nickName: String,
)

/** 닉네임 중복 확인 결과다. */
data class NicknameAvailabilityData(
    /** 앞뒤 공백을 제거해 실제 저장·비교에 사용한 닉네임이다. */
    val nickName: String,
    /** true이면 현재 사용자가 이 닉네임으로 변경할 수 있다. */
    val available: Boolean,
)
