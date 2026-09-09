package hs.project.steptune.steptuneserver.recommendation

import hs.project.steptune.steptuneserver.auth.AuthSessionRepository
import hs.project.steptune.steptuneserver.auth.SocialAccountRepository
import hs.project.steptune.steptuneserver.auth.SocialIdentity
import hs.project.steptune.steptuneserver.auth.SocialProvider
import hs.project.steptune.steptuneserver.auth.SocialTokenVerifierRegistry
import hs.project.steptune.steptuneserver.user.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.BDDMockito.given
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** 추천 생성 API의 인증, 요청 검증, 성공 JSON 계약을 실제 HTTP 변환 계층에서 고정한다. */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:step_tune_recommendation_contract;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.auth.google-client-id=test.apps.googleusercontent.com",
        "app.auth.jwt-secret=0123456789abcdef0123456789abcdef",
    ],
)
@AutoConfigureMockMvc
class MusicRecommendationControllerContractTests {
    /** 실제 소셜 제공자 호출 없이 테스트 사용자의 Access Token을 발급한다. */
    @MockitoBean
    lateinit var socialTokenVerifierRegistry: SocialTokenVerifierRegistry

    /** 실제 AI 호출 없이 HTTP 계약만 고정된 추천 결과로 검증한다. */
    @MockitoBean
    lateinit var generateMusicRecommendationUseCase: GenerateMusicRecommendationUseCase

    /** Spring Security와 JSON 변환을 포함해 추천 HTTP API를 호출한다. */
    @Autowired
    lateinit var mockMvc: MockMvc

    /** 로그인 응답에서 발급된 Access Token을 읽는다. */
    @Autowired
    lateinit var objectMapper: ObjectMapper

    /** 테스트 데이터 정리를 위한 Refresh Token 저장소다. */
    @Autowired
    lateinit var authSessionRepository: AuthSessionRepository

    /** 테스트 데이터 정리를 위한 소셜 계정 저장소다. */
    @Autowired
    lateinit var socialAccountRepository: SocialAccountRepository

    /** 테스트 데이터 정리를 위한 사용자 저장소다. */
    @Autowired
    lateinit var userRepository: UserRepository

    /** 각 계약 테스트가 독립적으로 로그인 사용자를 만들도록 인증 데이터를 비운다. */
    @BeforeEach
    fun cleanDatabase() {
        authSessionRepository.deleteAll()
        socialAccountRepository.deleteAll()
        userRepository.deleteAll()
    }

    /** 유효한 요청이 확정한 필드 이름과 중첩 구조로 성공 응답을 반환하는지 검증한다. */
    @Test
    fun `generate recommendation returns stable response contract`() {
        val accessToken = login()
        given(
            generateMusicRecommendationUseCase.generate(
                anyLong(),
                anyRecommendationRequest(),
            ),
        ).willReturn(recommendationData())

        mockMvc.post("/api/v1/music-recommendations/generate") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "recordDate": "2026-09-03",
                  "preferredMoods": ["ENERGETIC", "LIVELY"],
                  "preferredGenres": ["HIP_HOP", "RNB"],
                  "durationMinutes": 30
                }
                """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.code") { value(200) }
            jsonPath("$.data.recommendationId") { value("a03f850b-84df-4c80-bf0e-f37489f69665") }
            jsonPath("$.data.recordDate") { value("2026-09-03") }
            jsonPath("$.data.stepSummary.todayStepCount") { value(5000) }
            jsonPath("$.data.stepSummary.recent7DayAverage") { value(3000.0) }
            jsonPath("$.data.activityLevel") { value("HIGH") }
            jsonPath("$.data.durationMinutes") { value(30) }
            jsonPath("$.data.reason") { value("오늘은 평소보다 활동량이 많아 선호 장르에 맞는 Dynamite를 추천했어요.") }
            jsonPath("$.data.track.title") { value("Dynamite") }
            jsonPath("$.data.track.artist") { value("BTS") }
            jsonPath("$.data.track.searchQuery") { value("BTS Dynamite official audio") }
            jsonPath("$.data.tracks") { doesNotExist() }
            jsonPath("$.data.searchQueries") { doesNotExist() }
            jsonPath("$.data.musicMoods") { doesNotExist() }
            jsonPath("$.data.genres") { doesNotExist() }
            jsonPath("$.data.preferredMoods") { doesNotExist() }
            jsonPath("$.data.preferredGenres") { doesNotExist() }
            jsonPath("$.data.generatedAt") { value("2026-09-03T06:30:00Z") }
        }
    }

    /** 재생 시간과 장르 개수 제한을 위반한 요청이 AI 계층에 도달하기 전에 400으로 거절되는지 검증한다. */
    @Test
    fun `generate recommendation validates request limits`() {
        val accessToken = login()

        mockMvc.post("/api/v1/music-recommendations/generate") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "recordDate": "2026-09-03",
                  "preferredMoods": ["CALM", "ENERGETIC", "EMOTIONAL"],
                  "preferredGenres": ["BALLAD", "HIP_HOP", "RNB", "POP"],
                  "durationMinutes": 5
                }
                """.trimIndent()
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value(400) }
        }
    }

    /** 추천 생성은 공개 API가 아니므로 Access Token이 없는 요청을 401로 거절하는지 검증한다. */
    @Test
    fun `generate recommendation requires access token`() {
        mockMvc.post("/api/v1/music-recommendations/generate") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"recordDate":"2026-09-03"}"""
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    /** AI 설정 누락/한도 초과/잘못된 응답도 code/message/data 형식을 유지하는지 검증한다. */
    @Test
    fun `recommendation errors use common API envelope`() {
        val accessToken = login()
        listOf(
            MusicRecommendationUnavailableException() to 503,
            MusicRecommendationRateLimitException() to 429,
            MusicRecommendationInvalidResponseException() to 502,
        ).forEach { (exception, expectedStatus) ->
            doThrow(exception).`when`(generateMusicRecommendationUseCase)
                .generate(anyLong(), anyRecommendationRequest())
            mockMvc.post("/api/v1/music-recommendations/generate") {
                header("Authorization", "Bearer $accessToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"recordDate":"2026-09-03"}"""
            }.andExpect {
                status { isEqualTo(expectedStatus) }
                jsonPath("$.code") { value(expectedStatus) }
                jsonPath("$.message") { isNotEmpty() }
                jsonPath("$.data") { value(null) }
            }
        }
    }

    /** Mockito any가 반환하는 null 때문에 Kotlin의 non-null 인자 검사가 실패하지 않도록 테스트 값을 제공한다. */
    private fun anyRecommendationRequest(): GenerateMusicRecommendationRequest =
        any(GenerateMusicRecommendationRequest::class.java)
            ?: GenerateMusicRecommendationRequest(LocalDate.of(2026, 9, 3))

    /** 테스트 사용자를 소셜 로그인시키고 추천 보호 API에 사용할 Access Token을 반환한다. */
    private fun login(): String {
        given(socialTokenVerifierRegistry.verify(SocialProvider.GOOGLE, "recommendation-token")).willReturn(
            SocialIdentity(
                provider = SocialProvider.GOOGLE,
                subject = "recommendation-subject",
                email = "recommendation@example.com",
            ),
        )
        val result = mockMvc.post("/api/v1/auth/social") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"provider":"google","token":"recommendation-token"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)
            .get("data")
            .get("accessToken")
            .stringValue()
    }

    /** 성공 응답 JSON 필드를 변경하지 못하도록 계약 테스트에서 사용할 고정 추천 결과다. */
    private fun recommendationData(): MusicRecommendationData = MusicRecommendationData(
        recommendationId = "a03f850b-84df-4c80-bf0e-f37489f69665",
        recordDate = LocalDate.parse("2026-09-03"),
        stepSummary = RecommendationStepSummaryData(
            todayStepCount = 5000,
            recent7DayAverage = BigDecimal("3000.00"),
            recordedDayCount = 3,
            differenceFromAverage = BigDecimal("2000.00"),
            changeRatePercent = BigDecimal("66.67"),
        ),
        activityLevel = RecommendationActivityLevel.HIGH,
        durationMinutes = 30,
        reason = "오늘은 평소보다 활동량이 많아 선호 장르에 맞는 Dynamite를 추천했어요.",
        track = RecommendedTrackData(
            title = "Dynamite",
            artist = "BTS",
            searchQuery = "BTS Dynamite official audio",
        ),
        generatedAt = Instant.parse("2026-09-03T06:30:00Z"),
    )
}
