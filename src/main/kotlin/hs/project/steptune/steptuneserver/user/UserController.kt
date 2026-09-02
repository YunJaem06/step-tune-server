package hs.project.steptune.steptuneserver.user

import hs.project.steptune.steptuneserver.auth.requireUserId
import hs.project.steptune.steptuneserver.common.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Android 앱의 현재 사용자 프로필 API를 정의한다. */
@RestController
@RequestMapping("/api/v1/me")
class UserController(
    /** Controller가 직접 DB를 수정하지 않고 사용자 업무 규칙을 위임하는 Service다. */
    private val userService: UserService,
) {
    /** Access Token으로 인증된 현재 사용자의 최신 정보를 반환한다. */
    @GetMapping("/profile")
    fun me(@AuthenticationPrincipal jwt: Jwt): ApiResponse<UserData> =
        ApiResponse.success(userService.getUser(jwt.requireUserId()))

    /** 현재 사용자의 닉네임을 변경하고 변경된 사용자 정보를 반환한다. */
    @PatchMapping("/nickname")
    fun updateNickname(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: UpdateNicknameRequest,
    ): ApiResponse<UserData> =
        ApiResponse.success(userService.updateNickname(jwt.requireUserId(), request))

    /** 프로필 저장 전에 입력한 닉네임을 사용할 수 있는지 확인한다. */
    @GetMapping("/nickname/availability")
    fun checkNicknameAvailability(
        @AuthenticationPrincipal jwt: Jwt,
        // 파라미터가 빠져도 Spring 기본 오류 대신 Service의 공통 400 JSON으로 응답하게 빈 값을 사용한다.
        @RequestParam(defaultValue = "") nickName: String,
    ): ApiResponse<NicknameAvailabilityData> =
        ApiResponse.success(userService.checkNicknameAvailability(jwt.requireUserId(), nickName))

    /** 현재 사용자와 소셜 연결, 모든 Refresh Token 세션을 영구 삭제한다. */
    @DeleteMapping("/account")
    fun deleteMe(@AuthenticationPrincipal jwt: Jwt): ApiResponse<Any> {
        userService.deleteUser(jwt.requireUserId())
        return ApiResponse.successWithoutData()
    }
}
