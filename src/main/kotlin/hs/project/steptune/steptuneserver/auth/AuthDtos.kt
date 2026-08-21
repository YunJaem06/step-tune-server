package hs.project.steptune.steptuneserver.auth

import jakarta.validation.constraints.NotBlank
import java.util.UUID

/** 기존 Google 전용 로그인 요청. idToken은 Google이 서명한 사용자 인증 결과다. */
data class GoogleLoginRequest(
    /** 빈 토큰은 외부 검증을 시도하지 않고 요청 검증 단계에서 거절한다. */
    @field:NotBlank
    val idToken: String,
)

/** 세 소셜 제공자가 함께 사용하는 최소 로그인 요청. */
data class SocialLoginRequest(
    /** 어떤 제공자의 검증기를 사용할지 결정한다. */
    val provider: SocialProvider,
    /** Google은 ID Token, Kakao·Naver는 Access Token을 전달한다. */
    @field:NotBlank
    val token: String,
)

/** 자동 로그인에 사용하는 Refresh Token 요청. */
data class RefreshRequest(
    /** 앱의 안전한 로컬 저장소에 보관했다가 Access Token 갱신 시 보내는 원문이다. */
    @field:NotBlank
    val refreshToken: String,
)

/** 서버 세션을 폐기할 때 사용하는 로그아웃 요청. */
data class LogoutRequest(
    /** 서버가 어떤 로그인 세션을 폐기할지 찾는 데 사용하는 현재 Refresh Token이다. */
    @field:NotBlank
    val refreshToken: String,
)

/** 앱 화면과 로컬 캐시에 필요한 최소 사용자 정보다. */
data class UserData(
    /** 서버가 발급한 Step Tune 내부 사용자 식별자다. */
    val userId: UUID,
    /** 신규 사용자는 랜덤으로 받고, 추후 프로필 기능에서 변경할 값이다. */
    val nickName: String,
)

/** 로그인과 자동 로그인 성공 시 data 안에 들어가는 인증 결과다. */
data class AuthData(
    /** 일반 API의 Authorization: Bearer 헤더에 넣는 짧은 수명의 JWT다. */
    val accessToken: String,
    /** Access Token이 만료될 때까지 남은 초다. 앱의 선제 갱신 판단에 사용한다. */
    val accessTokenExpiresIn: Long,
    /** Access Token을 재발급받기 위한 긴 수명의 일회성 회전 토큰이다. */
    val refreshToken: String,
    /** 로그인한 사용자 표시 정보다. */
    val userData: UserData,
)
