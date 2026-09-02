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

    /** 여러 날짜를 저장하고 같은 날짜를 다시 보내도 행 추가 없이 최신 총합으로 갱신되는지 검증한다. */
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
    private fun saveOneDay(accessToken: String, stepCount: Int) {
        mockMvc.put("/api/v1/steps/daily-records/sync") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "records": [
                    {
                      "recordDate": "2026-09-02",
                      "stepCount": $stepCount,
                      "measuredAt": "2026-09-02T15:00:00+09:00"
                    }
                  ]
                }
                """.trimIndent()
        }.andExpect {
            status { isOk() }
        }
    }
}

