package hs.project.steptune.steptuneserver.step

import hs.project.steptune.steptuneserver.auth.AuthSessionRepository
import hs.project.steptune.steptuneserver.auth.SocialAccountRepository
import hs.project.steptune.steptuneserver.auth.SocialIdentity
import hs.project.steptune.steptuneserver.auth.SocialProvider
import hs.project.steptune.steptuneserver.auth.SocialTokenVerifierRegistry
import hs.project.steptune.steptuneserver.user.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import kotlin.test.assertEquals

/** 걸음 API의 JWT 사용자 분리, upsert, 조회, 탈퇴 연동을 실제 HTTP와 H2 DB로 검증한다. */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:step_tune_steps;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.auth.google-client-id=test.apps.googleusercontent.com",
        "app.auth.jwt-secret=0123456789abcdef0123456789abcdef",
    ],
)
@AutoConfigureMockMvc
class StepRecordControllerIntegrationTests {
    /** 실제 Google 서버 호출만 가짜 신원으로 바꾸고 Step Tune 인증/DB 계층은 그대로 실행한다. */
    @MockitoBean
    lateinit var socialTokenVerifierRegistry: SocialTokenVerifierRegistry

    /** Spring Security 필터를 포함한 HTTP 요청을 실제 포트 없이 실행한다. */
    @Autowired
    lateinit var mockMvc: MockMvc

    /** 로그인 응답 JSON에서 Access Token을 읽는 데 사용한다. */
    @Autowired
    lateinit var objectMapper: ObjectMapper

    /** 테스트가 저장한 일별 걸음 행의 수와 삭제 결과를 확인한다. */
    @Autowired
    lateinit var dailyStepRecordRepository: DailyStepRecordRepository

    /** 인증 테이블을 외래 키 자식부터 정리하기 위한 저장소다. */
    @Autowired
    lateinit var authSessionRepository: AuthSessionRepository

    /** 인증 테이블을 외래 키 자식부터 정리하기 위한 저장소다. */
    @Autowired
    lateinit var socialAccountRepository: SocialAccountRepository

    /** 각 테스트의 사용자를 새로 만들기 위한 저장소다. */
    @Autowired
    lateinit var userRepository: UserRepository

    /** 각 테스트가 완전히 독립적으로 실행되도록 자식 테이블부터 데이터를 비운다. */
    @BeforeEach
    fun cleanDatabase() {
        dailyStepRecordRepository.deleteAll()
        authSessionRepository.deleteAll()
        socialAccountRepository.deleteAll()
        userRepository.deleteAll()
    }

    /** 여러 날짜를 저장하고 같은 날짜의 더 큰 총합을 다시 보내면 행 추가 없이 갱신되는지 검증한다. */
    @Test
    fun `daily step sync creates and updates one row per user and date`() {
        val accessToken = login("step-sync-token", "step-sync-subject")

        // 날짜 순서를 섞어 보내도 응답과 이후 이력은 날짜 오름차순이어야 한다.
        mockMvc.put("/api/v1/steps/daily-records/sync") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "records": [
                    {
                      "recordDate": "2026-09-02",
                      "stepCount": 2200,
                      "measuredAt": "2026-09-02T15:30:00+09:00"
                    },
                    {
                      "recordDate": "2026-09-01",
                      "stepCount": 8400,
                      "measuredAt": "2026-09-01T23:55:00+09:00"
                    }
                  ]
                }
                """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.code") { value(200) }
            jsonPath("$.data.records[0].recordDate") { value("2026-09-01") }
            jsonPath("$.data.records[0].stepCount") { value(8400) }
            jsonPath("$.data.records[1].recordDate") { value("2026-09-02") }
            jsonPath("$.data.syncTime") { isNotEmpty() }
        }
        assertEquals(2, dailyStepRecordRepository.count())

        // 9월 2일의 최신 하루 총합을 다시 보내면 기존 행이 UPDATE되어야 한다.
        mockMvc.put("/api/v1/steps/daily-records/sync") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "records": [
                    {
                      "recordDate": "2026-09-02",
                      "stepCount": 3100,
                      "measuredAt": "2026-09-02T16:10:00+09:00"
                    }
                  ]
                }
                """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.records[0].stepCount") { value(3100) }
        }
        assertEquals(2, dailyStepRecordRepository.count())

        mockMvc.get("/api/v1/steps/daily-records/by-date") {
            header("Authorization", "Bearer $accessToken")
            param("recordDate", "2026-09-02")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.record.recordDate") { value("2026-09-02") }
            jsonPath("$.data.record.stepCount") { value(3100) }
        }

        mockMvc.get("/api/v1/steps/daily-records/history") {
            header("Authorization", "Bearer $accessToken")
            param("from", "2026-09-01")
            param("to", "2026-09-02")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.from") { value("2026-09-01") }
            jsonPath("$.data.to") { value("2026-09-02") }
            jsonPath("$.data.records.length()") { value(2) }
            jsonPath("$.data.records[1].stepCount") { value(3100) }
        }
    }

    /** 늦게 도착한 낮은 총합이 더 최신인 서버 기록과 측정·수정 시각을 되돌리지 않는지 검증한다. */
    @Test
    fun `daily step sync ignores a stale lower total`() {
        val accessToken = login("stale-step-token", "stale-step-subject")
        val recordDate = LocalDate.parse("2026-09-02")

        // 먼저 포그라운드 서비스가 계산한 더 큰 총합을 저장한 상황을 만든다.
        saveOneDay(accessToken, 1001, recordDate.toString())
        val recordBeforeStaleRequest = requireNotNull(
            dailyStepRecordRepository.findByUserIdAndRecordDate(
                userId = requireNotNull(userRepository.findAll().single().id),
                recordDate = recordDate,
            ),
        )
        val measuredAtBeforeStaleRequest = recordBeforeStaleRequest.measuredAt
        val updatedAtBeforeStaleRequest = recordBeforeStaleRequest.updatedAt

        // 이전 1,000걸음 요청이 더 늦은 측정 시각을 달고 도착해도 값과 시각을 모두 유지해야 한다.
        mockMvc.put("/api/v1/steps/daily-records/sync") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "records": [
                    {
                      "recordDate": "2026-09-02",
                      "stepCount": 1000,
                      "measuredAt": "2026-09-02T16:00:00+09:00"
                    }
                  ]
                }
                """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.records[0].stepCount") { value(1001) }
            jsonPath("$.data.records[0].measuredAt") { value(measuredAtBeforeStaleRequest.toString()) }
            jsonPath("$.data.records[0].updatedAt") { value(updatedAtBeforeStaleRequest.toString()) }
        }

        val recordAfterStaleRequest = requireNotNull(
            dailyStepRecordRepository.findByUserIdAndRecordDate(
                userId = requireNotNull(userRepository.findAll().single().id),
                recordDate = recordDate,
            ),
        )
        assertEquals(1001, recordAfterStaleRequest.stepCount)
        assertEquals(measuredAtBeforeStaleRequest, recordAfterStaleRequest.measuredAt)
        assertEquals(updatedAtBeforeStaleRequest, recordAfterStaleRequest.updatedAt)
    }

    /** 요청 내부 날짜 중복과 음수 걸음 수가 DB 저장 전에 400으로 거절되는지 검증한다. */
    @Test
    fun `daily step sync rejects duplicate dates and negative counts`() {
        val accessToken = login("invalid-step-token", "invalid-step-subject")

        mockMvc.put("/api/v1/steps/daily-records/sync") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "records": [
                    {"recordDate":"2026-09-02","stepCount":100,"measuredAt":"2026-09-02T01:00:00Z"},
                    {"recordDate":"2026-09-02","stepCount":200,"measuredAt":"2026-09-02T02:00:00Z"}
                  ]
                }
                """.trimIndent()
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value(400) }
            jsonPath("$.message") { value("Duplicate step record date: 2026-09-02") }
        }

        mockMvc.put("/api/v1/steps/daily-records/sync") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "records": [
                    {"recordDate":"2026-09-02","stepCount":-1,"measuredAt":"2026-09-02T01:00:00Z"}
                  ]
                }
                """.trimIndent()
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value(400) }
        }
        assertEquals(0, dailyStepRecordRepository.count())
    }

    /** JWT의 userId가 소유권 기준이 되고 회원 탈퇴 시 그 사용자의 걸음 기록도 함께 삭제되는지 검증한다. */
    @Test
    fun `step records are isolated by authenticated user and cascade on account deletion`() {
        val firstAccessToken = login("first-step-token", "first-step-subject")
        val secondAccessToken = login("second-step-token", "second-step-subject")

        saveOneDay(firstAccessToken, 1111)
        saveOneDay(secondAccessToken, 2222)
        assertEquals(2, dailyStepRecordRepository.count())

        // 요청 본문에 userId가 없으므로 서버는 각 JWT 소유자의 기록 한 건만 돌려준다.
        mockMvc.get("/api/v1/steps/daily-records/history") {
            header("Authorization", "Bearer $firstAccessToken")
            param("from", "2026-09-02")
            param("to", "2026-09-02")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.records.length()") { value(1) }
            jsonPath("$.data.records[0].stepCount") { value(1111) }
        }

        mockMvc.delete("/api/v1/me/account") {
            header("Authorization", "Bearer $firstAccessToken")
        }.andExpect {
            status { isOk() }
        }

        // 첫 사용자 기록은 CASCADE로 삭제되고 두 번째 사용자 기록은 그대로 남아야 한다.
        assertEquals(1, dailyStepRecordRepository.count())
        mockMvc.get("/api/v1/steps/daily-records/by-date") {
            header("Authorization", "Bearer $secondAccessToken")
            param("recordDate", "2026-09-02")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.record.stepCount") { value(2222) }
        }
    }

    /** 기준일 포함 최근 7일의 실제 기록만 평균에 포함하고 다른 사용자의 기록은 제외하는지 검증한다. */
    @Test
    fun `weekly statistics calculate authenticated user records only`() {
        val firstAccessToken = login("first-statistics-token", "first-statistics-subject")
        val secondAccessToken = login("second-statistics-token", "second-statistics-subject")

        // 9월 3일 기준 최근 7일은 8월 28일부터이며, 기록이 없는 날짜는 평균에서 제외한다.
        saveOneDay(firstAccessToken, 1000, "2026-08-28")
        saveOneDay(firstAccessToken, 3000, "2026-09-01")
        saveOneDay(firstAccessToken, 5000, "2026-09-03")
        // 같은 날짜의 다른 사용자 기록은 첫 사용자의 평균에 섞이면 안 된다.
        saveOneDay(secondAccessToken, 9000, "2026-09-03")

        mockMvc.get("/api/v1/steps/statistics/weekly") {
            header("Authorization", "Bearer $firstAccessToken")
            param("recordDate", "2026-09-03")
        }.andExpect {
            status { isOk() }
            jsonPath("$.code") { value(200) }
            jsonPath("$.data.recordDate") { value("2026-09-03") }
            jsonPath("$.data.todayStepCount") { value(5000) }
            jsonPath("$.data.recent7DayAverage") { value(3000.0) }
            jsonPath("$.data.recordedDayCount") { value(3) }
            jsonPath("$.data.differenceFromAverage") { value(2000.0) }
            jsonPath("$.data.changeRatePercent") { value(66.67) }
        }
    }

    /** 잘못된 기준 날짜는 400, 아직 동기화되지 않은 정상 날짜는 404로 구분하는지 검증한다. */
    @Test
    fun `weekly statistics distinguish invalid and missing record dates`() {
        val accessToken = login("missing-statistics-token", "missing-statistics-subject")

        mockMvc.get("/api/v1/steps/statistics/weekly") {
            header("Authorization", "Bearer $accessToken")
            param("recordDate", "not-a-date")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value(400) }
            jsonPath("$.message") { value("recordDate must be a valid YYYY-MM-DD date") }
        }

        mockMvc.get("/api/v1/steps/statistics/weekly") {
            header("Authorization", "Bearer $accessToken")
            param("recordDate", "2026-09-03")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value(404) }
            jsonPath("$.message") { value("Step record not found: 2026-09-03") }
        }

        // 보호 API이므로 Access Token이 없는 요청은 Controller에 도달하기 전에 거절되어야 한다.
        mockMvc.get("/api/v1/steps/statistics/weekly") {
            param("recordDate", "2026-09-03")
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    /** 실제 추천 엔진 연결 전에도 기준일 걸음 누락과 기능 준비 상태를 서로 다른 오류로 반환하는지 검증한다. */
    @Test
    fun `recommendation contract checks step record before unavailable engine`() {
        val accessToken = login("pending-recommendation-token", "pending-recommendation-subject")
        val requestBody =
            """
            {
              "recordDate": "2026-09-03",
              "preferredMoods": ["ENERGETIC", "LIVELY"],
              "preferredGenres": ["HIP_HOP", "RNB"],
              "durationMinutes": 30
            }
            """.trimIndent()

        // 기준일 걸음이 없으면 Android가 먼저 걸음을 동기화할 수 있도록 404를 반환한다.
        mockMvc.post("/api/v1/music-recommendations/generate") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = requestBody
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.message") { value("Step record not found: 2026-09-03") }
        }

        saveOneDay(accessToken, 5000, "2026-09-03")

        // 걸음은 준비됐지만 아직 AI 추천 Service가 없으므로 가짜 성공 데이터 대신 503을 반환한다.
        mockMvc.post("/api/v1/music-recommendations/generate") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = requestBody
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.message") { value("Music recommendation engine is not configured") }
        }
    }

    /** 최근 7일 평균이 0일 때 증감률을 계산하지 않고 null로 안전하게 반환하는지 검증한다. */
    @Test
    fun `weekly statistics return null change rate when average is zero`() {
        val accessToken = login("zero-statistics-token", "zero-statistics-subject")
        saveOneDay(accessToken, 0, "2026-09-03")

        mockMvc.get("/api/v1/steps/statistics/weekly") {
            header("Authorization", "Bearer $accessToken")
            param("recordDate", "2026-09-03")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.todayStepCount") { value(0) }
            jsonPath("$.data.recent7DayAverage") { value(0.0) }
            jsonPath("$.data.differenceFromAverage") { value(0.0) }
            jsonPath("$.data.changeRatePercent") { doesNotExist() }
        }
    }

    /** 한 테스트 사용자를 소셜 로그인시키고 보호 API 호출에 필요한 Access Token만 반환한다. */
    private fun login(token: String, subject: String): String {
        given(socialTokenVerifierRegistry.verify(SocialProvider.GOOGLE, token)).willReturn(
            SocialIdentity(
                provider = SocialProvider.GOOGLE,
                subject = subject,
                email = "$subject@example.com",
            ),
        )
        val result = mockMvc.post("/api/v1/auth/social") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"provider":"google","token":"$token"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)
            .get("data")
            .get("accessToken")
            .stringValue()
    }

    /** 사용자 분리 테스트에서 한 날짜 기록을 만드는 반복 HTTP 요청이다. */
    private fun saveOneDay(
        accessToken: String,
        stepCount: Int,
        recordDate: String = "2026-09-02",
    ) {
        mockMvc.put("/api/v1/steps/daily-records/sync") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "records": [
                    {
                      "recordDate": "$recordDate",
                      "stepCount": $stepCount,
                      "measuredAt": "${recordDate}T15:00:00+09:00"
                    }
                  ]
                }
                """.trimIndent()
        }.andExpect {
            status { isOk() }
        }
    }
}
