package hs.project.steptune.steptuneserver.auth

import hs.project.steptune.steptuneserver.common.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Android 앱이 호출하는 인증 HTTP API를 정의한다.
 *
 * Controller는 요청 JSON을 DTO로 변환하고 Service에 업무 처리를 위임한 뒤,
 * 모든 결과를 Ranking App과 같은 [ApiResponse] 형식으로 감싸 반환한다.
 */
@RestController
@RequestMapping("/api/v1")
class AuthController(
    /** Controller가 직접 DB를 다루지 않고 모든 인증 업무 규칙을 위임하는 Service다. */
    private val authService: AuthService,
) {
    /** 이전 Google 전용 앱과의 호환을 위해 유지하는 Google 로그인 API다. */
    @PostMapping("/auth/google")
    fun loginWithGoogle(@Valid @RequestBody request: GoogleLoginRequest): ApiResponse<AuthData> =
        ApiResponse.success(authService.loginWithGoogle(request))

    /** Google·Kakao·Naver가 공통으로 사용하는 소셜 로그인 API다. */
    @PostMapping("/auth/social")
    fun loginWithSocial(@Valid @RequestBody request: SocialLoginRequest): ApiResponse<AuthData> =
        ApiResponse.success(authService.loginWithSocial(request))

    /** 저장된 Refresh Token으로 자동 로그인하고 두 토큰을 모두 새로 발급한다. */
    @PostMapping("/auth/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): ApiResponse<AuthData> =
        ApiResponse.success(authService.refresh(request))

    /** 현재 Refresh Token 세션을 폐기하여 이후 재발급에 사용하지 못하게 한다. */
    @PostMapping("/auth/logout")
    fun logout(@Valid @RequestBody request: LogoutRequest): ApiResponse<Any> {
        authService.logout(request)
        return ApiResponse.successWithoutData()
    }

    /**
     * Access Token으로 인증된 현재 사용자의 최신 정보를 반환한다.
     * userId를 요청에서 받지 않고 검증된 JWT의 subject에서 꺼내므로 다른 사용자를 사칭할 수 없다.
     */
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal jwt: Jwt): ApiResponse<UserData> =
        ApiResponse.success(authService.getUser(UUID.fromString(jwt.subject)))
}
