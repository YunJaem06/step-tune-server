package hs.project.steptune.steptuneserver.recommendation

import hs.project.steptune.steptuneserver.step.StepStatisticsService
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * 로그인 사용자의 통계 조회 → AI 호출 → 앱 응답 조립을 담당한다. 추천 내용은 서버 DB에 저장하지 않는다.
 * 이 메서드 전체에 @Transactional을 붙이지 않는다. 별도 통계 Service의 읽기 트랜잭션이 끝난 후 AI를 호출한다.
 */
@Service
class MusicRecommendationService(
    /** 추천 생성 전에 로그인 사용자의 기준일 걸음과 최근 통계를 검증한다. */
    private val stepStatisticsService: StepStatisticsService,
    /** 외부 AI 교체가 Controller/API 계약에 영향을 주지 않도록 공통 인터페이스를 사용한다. */
    private val musicRecommendationClient: MusicRecommendationClient,
) : GenerateMusicRecommendationUseCase {
    /** 현재 사용자 기록만 읽고, AI가 통계나 사용자 식별자를 임의 생성하지 못하게 서버 값으로 응답을 조립한다. */
    override fun generate(
        userId: Long,
        request: GenerateMusicRecommendationRequest,
    ): MusicRecommendationData {
        val statistics = stepStatisticsService.getWeeklyStatistics(userId, request.recordDate.toString())
        val summary = RecommendationStepSummaryData(
            todayStepCount = statistics.todayStepCount,
            recent7DayAverage = statistics.recent7DayAverage,
            recordedDayCount = statistics.recordedDayCount,
            differenceFromAverage = statistics.differenceFromAverage,
            changeRatePercent = statistics.changeRatePercent,
        )
        // 통계 DTO만 남은 시점이며 외부 네트워크 대기 중에는 DB 트랜잭션/커넥션을 점유하지 않는다.
        val recommendation = musicRecommendationClient.recommend(summary, request)
        return MusicRecommendationData(
            recommendationId = UUID.randomUUID().toString(),
            recordDate = statistics.recordDate,
            stepSummary = summary,
            activityLevel = recommendation.activityLevel,
            durationMinutes = request.durationMinutes,
            reason = recommendation.reason,
            track = RecommendedTrackData(
                title = recommendation.trackTitle,
                artist = recommendation.trackArtist,
                // 자유 형식 AI 검색어를 사용하지 않아 playlist/mix 같은 일반 검색으로 바뀌는 것을 막는다.
                searchQuery = "${recommendation.trackArtist} ${recommendation.trackTitle} official audio",
            ),
            generatedAt = Instant.now(),
        )
    }
}
