package hs.project.steptune.steptuneserver.recommendation

import hs.project.steptune.steptuneserver.config.GeminiProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 실제 API 키/네트워크 없이 Gemini 요청 형식과 신뢰할 수 없는 응답의 방어 처리를 검증한다. */
class GeminiMusicRecommendationClientTests {
    /** Kotlin DTO를 직렬화하는 테스트 전용 JSON 변환기다. */
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    /** 포트를 열지 않는 가짜 HTTP 전송 계층을 붙인다. */
    private val builder = RestClient.builder().baseUrl("https://generativelanguage.googleapis.com")
    private val server = MockRestServiceServer.bindTo(builder).build()
    /** 실제 키가 아닌 고정 테스트 값만 사용한다. */
    private val client = GeminiMusicRecommendationClient(
        GeminiProperties(enabled = true, apiKey = "test-gemini-key"), builder.build(), mapper,
    )
    /** 사용자 식별 정보 없이 AI에 보낼 통계 예시다. */
    private val summary = RecommendationStepSummaryData(5000, BigDecimal("3000.00"), 3, BigDecimal("2000.00"), BigDecimal("66.67"))
    /** 날짜는 외부 요청에서 제외되는지 함께 검증한다. */
    private val request = GenerateMusicRecommendationRequest(
        LocalDate.of(2026, 9, 3),
        preferredMoods = listOf(MusicMood.ENERGETIC),
        preferredGenres = listOf(MusicGenre.POP),
    )

    /** endpoint/헤더/개인정보 제외/JSON 스키마/최종 매핑을 검증한다. */
    @Test
    fun `sends minimal structured request and parses a recommendation`() {
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("x-goog-api-key", "test-gemini-key"))
            .andExpect { httpRequest ->
                val body = mapper.readTree((httpRequest as MockClientHttpRequest).bodyAsString)
                val input = mapper.readTree(body.path("contents")[0].path("parts")[0].path("text").stringValue())
                assertEquals(setOf("stepSummary", "preferredMoods", "preferredGenres", "durationMinutes"), input.propertyNames().toSet())
                assertEquals(5000, input.path("stepSummary").path("todayStepCount").intValue())
                assertEquals(30, input.path("durationMinutes").intValue())
                assertEquals("ENERGETIC", input.path("preferredMoods")[0].stringValue())
                assertEquals("POP", input.path("preferredGenres")[0].stringValue())
                assertFalse(input.toString().contains("2026-09-03"))
                assertFalse(body.toString().contains("test-gemini-key"))
                assertTrue(body.path("systemInstruction").toString().contains("exactly one specific song"))
                assertEquals("application/json", body.path("generationConfig").path("responseMimeType").stringValue())
                assertEquals(1, body.path("generationConfig").path("candidateCount").intValue())
                assertEquals(2048, body.path("generationConfig").path("maxOutputTokens").intValue())
                val schema = body.path("generationConfig").path("responseJsonSchema")
                assertEquals(setOf("activityLevel", "reason", "track"), schema.path("properties").propertyNames().toSet())
                assertEquals(setOf("title", "artist"), schema.path("properties").path("track").path("properties").propertyNames().toSet())
                assertTrue(body.path("tools").isMissingNode)
            }
            .andRespond(withSuccess(envelope(validJson), MediaType.APPLICATION_JSON))

        val result = client.recommend(summary, request)
        assertEquals(RecommendationActivityLevel.HIGH, result.activityLevel)
        assertEquals("Levitating", result.trackTitle)
        assertEquals("Dua Lipa", result.trackArtist)
        server.verify()
    }

    /** 키/활성화가 없으면 가짜 HTTP 계층에도 도달하지 않아 다른 API 개발 중 외부 요청이 생기지 않는다. */
    @Test
    fun `disabled or unconfigured client does not call provider`() {
        listOf(GeminiProperties(apiKey = "test-key"), GeminiProperties(enabled = true, apiKey = " ")).forEach { properties ->
            val unavailable = GeminiMusicRecommendationClient(properties, builder.build(), mapper)
            assertFailsWith<MusicRecommendationUnavailableException> { unavailable.recommend(summary, request) }
        }
        server.verify()
    }

    /** 429를 인증 만료로 오인하지 않으며 자동 재시도로 할당량을 더 쓰지 않는다. */
    @Test
    fun `quota errors are returned without retry or provider body leakage`() {
        server.expect(requestTo(endpoint)).andRespond(
            withStatus(HttpStatus.TOO_MANY_REQUESTS).body("secret provider diagnostic test-gemini-key"),
        )
        val error = assertFailsWith<MusicRecommendationRateLimitException> { client.recommend(summary, request) }
        assertFalse(error.message.orEmpty().contains("test-gemini-key"))
        server.verify()
    }

    /** 잘못된 키/모델이나 외부 장애를 앱 사용자의 401로 반환하지 않는다. */
    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404, 500, 503])
    fun `provider HTTP errors are sanitized`(status: Int) {
        server.expect(requestTo(endpoint)).andRespond(withStatus(HttpStatus.valueOf(status)).body("test-gemini-key"))
        val error = assertFailsWith<MusicRecommendationUnavailableException> { client.recommend(summary, request) }
        assertFalse(error.message.orEmpty().contains("test-gemini-key"))
        assertEquals(null, error.cause)
        server.verify()
    }

    /** 연결/응답 시간이 초과되면 재시도하지 않고 일반적인 외부 서비스 오류로 바꾼다. */
    @Test
    fun `timeouts are sanitized and not retried`() {
        server.expect(requestTo(endpoint)).andRespond(withException(SocketTimeoutException("test-gemini-key")))
        assertFailsWith<MusicRecommendationUnavailableException> { client.recommend(summary, request) }
        server.verify()
    }

    /** 중간 생성/안전 필터 종료는 JSON이 그럴듯하더라도 성공 결과로 사용하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = ["MAX_TOKENS", "SAFETY", "RECITATION", "OTHER"])
    fun `unfinished candidates are rejected`(reason: String) {
        server.expect(requestTo(endpoint)).andRespond(withSuccess(envelope(validJson, reason), MediaType.APPLICATION_JSON))
        assertFailsWith<MusicRecommendationInvalidResponseException> { client.recommend(summary, request) }
        server.verify()
    }

    /** 비정상 envelope와 응답 크기 초과, 최종 JSON 문법 오류를 검증한다. */
    @Test
    fun `rejects empty blocked malformed and oversized responses`() {
        val responses = listOf(
            "", "null", "not-json", "{}", "{\"candidates\":[]}",
            "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}",
            envelope("not-json"), envelope(validJson + " {}"), envelope(validJson) + " {}",
            " ".repeat(65537),
        )
        responses.forEach { response ->
            server.reset()
            server.expect(requestTo(endpoint)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON))
            assertFailsWith<MusicRecommendationInvalidResponseException> { client.recommend(summary, request) }
            server.verify()
        }
    }

    /** 올바른 JSON이어도 enum/한국어 이유/단일 곡 계약/필수 필드를 어기면 앱에 넘기지 않는다. */
    @Test
    fun `rejects invalid recommendation contracts`() {
        val changes: List<(ObjectNode) -> Unit> = listOf(
            { it.put("activityLevel", "UNKNOWN") },
            { it.put("reason", 123) },
            { it.put("reason", " ") },
            { it.put("reason", "English only reason") },
            { it.put("reason", "이 노래가 취향에 잘 맞아서 추천해요.") },
            { it.put("reason", "가".repeat(301)) },
            { it.remove("reason") },
            { it.put("recommendationId", "ai-generated-id") },
            { it.remove("track") },
            { (it.path("track") as ObjectNode).put("title", 123) },
            { (it.path("track") as ObjectNode).put("title", " ") },
            { (it.path("track") as ObjectNode).put("title", "https://example.com") },
            { (it.path("track") as ObjectNode).put("title", "Energetic Pop Playlist") },
            { (it.path("track") as ObjectNode).put("title", "Morning Mix") },
            { (it.path("track") as ObjectNode).put("title", "x".repeat(121)) },
            { (it.path("track") as ObjectNode).put("artist", "line\nbreak") },
            { (it.path("track") as ObjectNode).put("artist", "Various Artists") },
            { (it.path("track") as ObjectNode).put("searchQuery", "generic playlist") },
            { it.set("tracks", mapper.readTree("[]")) },
        )
        changes.forEach { change ->
            server.reset()
            val node = mapper.readTree(validJson) as ObjectNode
            change(node)
            server.expect(requestTo(endpoint)).andRespond(withSuccess(envelope(node.toString()), MediaType.APPLICATION_JSON))
            assertFailsWith<MusicRecommendationInvalidResponseException> { client.recommend(summary, request) }
            server.verify()
        }
    }

    /** 생각용 part에 잘못된 텍스트가 있어도 최종 답변과 섞지 않는다. */
    @Test
    fun `ignores thought parts and joins final answer parts`() {
        val response = mapper.writeValueAsString(mapOf("candidates" to listOf(mapOf(
            "finishReason" to "STOP",
            "content" to mapOf("parts" to listOf(
                mapOf("thought" to true, "text" to "not the answer"),
                mapOf("text" to validJson.take(20)), mapOf("text" to validJson.drop(20)),
            )),
        ))))
        server.expect(requestTo(endpoint)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON))
        assertEquals(RecommendationActivityLevel.HIGH, client.recommend(summary, request).activityLevel)
        server.verify()
    }

    /** 모델의 추천 JSON을 실제 Gemini의 candidates/parts 응답 형태로 감싼다. */
    private fun envelope(text: String, finishReason: String = "STOP"): String = mapper.writeValueAsString(
        mapOf("candidates" to listOf(mapOf(
            "finishReason" to finishReason,
            "content" to mapOf("parts" to listOf(mapOf("text" to text))),
        ))),
    )

    private companion object {
        /** API 키가 URL에 들어가지 않는 endpoint다. */
        const val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"
        /** Gemini가 한 곡과 한국어 추천 이유만 반환하는 정상 출력 예시다. */
        val validJson = """
            {"activityLevel":"HIGH",
             "reason":"오늘 5,000걸음은 최근 평균보다 높아, 선호한 활기찬 팝 분위기에 맞는 Dua Lipa의 Levitating을 추천해요.",
             "track":{"title":"Levitating","artist":"Dua Lipa"}}
        """.trimIndent()
    }
}
