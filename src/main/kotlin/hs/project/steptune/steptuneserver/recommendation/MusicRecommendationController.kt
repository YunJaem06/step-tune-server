package hs.project.steptune.steptuneserver.recommendation

import hs.project.steptune.steptuneserver.auth.requireUserId
import hs.project.steptune.steptuneserver.common.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Android가 로그인 사용자의 걸음과 취향으로 음악 추천을 요청하는 HTTP API다. */
@RestController
@RequestMapping("/api/v1/music-recommendations")
class MusicRecommendationController(
    /** Controller가 AI나 DB 구현을 알지 않도록 추천 생성 계약에만 의존한다. */
    private val generateMusicRecommendationUseCase: GenerateMusicRecommendationUseCase,
) {
    /** Access Token의 사용자와 검증된 추천 조건으로 새로운 음악 추천을 생성한다. */
    @PostMapping("/generate")
    fun generate(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: GenerateMusicRecommendationRequest,
    ): ApiResponse<MusicRecommendationData> =
        ApiResponse.success(generateMusicRecommendationUseCase.generate(jwt.requireUserId(), request))
}
