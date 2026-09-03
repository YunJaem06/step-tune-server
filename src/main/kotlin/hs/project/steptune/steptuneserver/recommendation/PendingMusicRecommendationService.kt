package hs.project.steptune.steptuneserver.recommendation

import hs.project.steptune.steptuneserver.step.StepStatisticsService
import org.springframework.stereotype.Service

/**
 * 추천 API 계약 단계에서 서버를 정상 실행하기 위한 임시 구현이다.
 * 실제 구현 전에도 기준일 걸음 존재 여부를 먼저 확인하고, 준비되지 않은 성공 데이터를 만들지 않고 503을 반환한다.
 */
@Service
class PendingMusicRecommendationService(
    /** 추천 생성 전에 로그인 사용자의 기준일 걸음과 최근 통계를 검증한다. */
    private val stepStatisticsService: StepStatisticsService,
) : GenerateMusicRecommendationUseCase {
    /** 걸음 기록이 있으면 다음 구현 단계가 필요함을 알리고, 없으면 기존 통계 예외로 404를 반환한다. */
    override fun generate(
        userId: Long,
        request: GenerateMusicRecommendationRequest,
    ): MusicRecommendationData {
        stepStatisticsService.getWeeklyStatistics(userId, request.recordDate.toString())
        throw MusicRecommendationUnavailableException()
    }
}
