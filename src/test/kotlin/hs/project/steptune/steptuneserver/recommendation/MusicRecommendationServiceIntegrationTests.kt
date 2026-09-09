package hs.project.steptune.steptuneserver.recommendation

import hs.project.steptune.steptuneserver.auth.UserNotFoundException
import hs.project.steptune.steptuneserver.step.DailyStepRecordEntity
import hs.project.steptune.steptuneserver.step.DailyStepRecordRepository
import hs.project.steptune.steptuneserver.step.StepRecordNotFoundException
import hs.project.steptune.steptuneserver.user.UserEntity
import hs.project.steptune.steptuneserver.user.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** H2에서 사용자 분리와 트랜잭션 경계를 검증하고 외부 AI만 가짜 구현으로 대체한다. */
@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:step_tune_recommendation_service;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "app.auth.google-client-id=test.apps.googleusercontent.com",
    "app.auth.jwt-secret=0123456789abcdef0123456789abcdef",
    "app.gemini.enabled=false",
])
class MusicRecommendationServiceIntegrationTests {
    /** 실제 서비스와 통계 조회/JPA 트랜잭션을 사용한다. */
    @Autowired
    lateinit var service: MusicRecommendationService
    /** 독립된 테스트 사용자를 만든다. */
    @Autowired
    lateinit var users: UserRepository
    /** 실제 엔티티로 기준일 통계를 준비한다. */
    @Autowired
    lateinit var records: DailyStepRecordRepository
    /** 네트워크/과금이 발생하지 않도록 외부 AI 연결만 대체한다. */
    @MockitoBean
    lateinit var client: MusicRecommendationClient

    /** FK 자식부터 정리하며 테스트 메서드 자체에 트랜잭션을 걸지 않는다. */
    @BeforeEach
    fun cleanDatabase() {
        records.deleteAll()
        users.deleteAll()
    }

    /** 다른 사용자 기록 제외, 통계 원본 유지, AI 호출 전 트랜잭션 종료, 매번 새 UUID 생성을 확인한다. */
    @Test
    fun `uses own statistics outside transaction and returns server owned metadata`() {
        val owner = users.save(UserEntity(nickname = "추천테스트주인"))
        val other = users.save(UserEntity(nickname = "추천테스트다른사용자"))
        saveSteps(owner, date.minusDays(1), 1000)
        saveSteps(owner, date, 5000)
        saveSteps(other, date, 200000)
        val request = GenerateMusicRecommendationRequest(date, durationMinutes = 45)
        val expectedSummary = RecommendationStepSummaryData(
            5000, BigDecimal("3000.00"), 2, BigDecimal("2000.00"), BigDecimal("66.67"),
        )
        var calls = 0
        doAnswer { invocation ->
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            val summary = invocation.getArgument<RecommendationStepSummaryData>(0)
            assertEquals(5000, summary.todayStepCount)
            assertEquals(BigDecimal("3000.00"), summary.recent7DayAverage)
            assertEquals(2, summary.recordedDayCount)
            assertEquals(request, invocation.getArgument<GenerateMusicRecommendationRequest>(1))
            calls++
            AiMusicRecommendation(
                RecommendationActivityLevel.HIGH,
                "오늘 걸음에 맞춘 팝 음악이에요.",
                "Levitating",
                "Dua Lipa",
            )
        }.`when`(client).recommend(expectedSummary, request)

        val before = Instant.now()
        val first = service.generate(requireNotNull(owner.id), request)
        val second = service.generate(requireNotNull(owner.id), request)
        assertEquals(2, calls)
        assertEquals(date, first.recordDate)
        assertEquals(45, first.durationMinutes)
        assertEquals(5000, first.stepSummary.todayStepCount)
        assertEquals(BigDecimal("3000.00"), first.stepSummary.recent7DayAverage)
        assertEquals("Levitating", first.track.title)
        assertEquals("Dua Lipa", first.track.artist)
        assertEquals("Dua Lipa Levitating official audio", first.track.searchQuery)
        assertEquals(first.recommendationId, UUID.fromString(first.recommendationId).toString())
        assertNotEquals(first.recommendationId, second.recommendationId)
        assertTrue(!first.generatedAt.isBefore(before) && !first.generatedAt.isAfter(Instant.now()))
        assertEquals(3L, records.count())
        assertEquals(2L, users.count())
    }

    /** 내 기준일 기록이 없으면 다른 사람 기록이 있어도 AI를 호출하지 않고 404 예외를 유지한다. */
    @Test
    fun `missing own record prevents external AI call`() {
        val owner = users.save(UserEntity(nickname = "기록없는사용자"))
        val other = users.save(UserEntity(nickname = "기록있는사용자"))
        saveSteps(other, date, 1000)
        assertFailsWith<StepRecordNotFoundException> {
            service.generate(requireNotNull(owner.id), GenerateMusicRecommendationRequest(date))
        }
        verifyNoInteractions(client)
    }

    /** 탈퇴한 사용자의 아직 만료되지 않은 JWT도 외부 AI 호출의 근거로 사용하지 않는다. */
    @Test
    fun `deleted user prevents external AI call`() {
        assertFailsWith<UserNotFoundException> { service.generate(Long.MAX_VALUE, GenerateMusicRecommendationRequest(date)) }
        verifyNoInteractions(client)
    }

    /** 테스트 날짜별 누적 걸음을 저장한다. AI 추천 결과를 저장하는 테이블은 만들지 않는다. */
    private fun saveSteps(user: UserEntity, date: LocalDate, count: Int) {
        records.save(DailyStepRecordEntity(user = user, recordDate = date, stepCount = count, measuredAt = Instant.parse("2026-09-03T01:00:00Z")))
    }

    private companion object {
        /** 시간대에 따라 테스트 결과가 바뀌지 않는 고정 기준일이다. */
        val date: LocalDate = LocalDate.of(2026, 9, 3)
    }
}
