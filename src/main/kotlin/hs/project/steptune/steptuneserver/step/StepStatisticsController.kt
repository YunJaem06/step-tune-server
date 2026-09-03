package hs.project.steptune.steptuneserver.step

import hs.project.steptune.steptuneserver.auth.requireUserId
import hs.project.steptune.steptuneserver.common.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Android 통계 화면과 향후 AI 추천 기능이 사용할 걸음 통계 HTTP API를 정의한다. */
@RestController
@RequestMapping("/api/v1/steps/statistics")
class StepStatisticsController(
    /** Controller가 계산 규칙을 갖지 않고 통계 Service에 업무를 위임한다. */
    private val stepStatisticsService: StepStatisticsService,
) {
    /** 로그인 사용자의 기준일 포함 최근 7일 걸음 통계를 반환한다. */
    @GetMapping("/weekly")
    fun getWeeklyStatistics(
        @AuthenticationPrincipal jwt: Jwt,
        // 누락된 값도 공통 날짜 검증을 거쳐 일관된 400 JSON으로 응답하도록 빈 기본값을 사용한다.
        @RequestParam(defaultValue = "") recordDate: String,
    ): ApiResponse<WeeklyStepStatisticsData> =
        ApiResponse.success(stepStatisticsService.getWeeklyStatistics(jwt.requireUserId(), recordDate))
}
