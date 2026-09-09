package hs.project.steptune.steptuneserver.recommendation

import hs.project.steptune.steptuneserver.config.GeminiProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException

/** Gemini generateContent REST API 연결부다. 서버가 생성한 집계/취향만 전송하고 모델 응답을 신뢰하지 않는다. */
@Component
class GeminiMusicRecommendationClient(
    /** 활성화 여부와 서버 전용 비밀키/모델/출력 제한이다. */
    private val properties: GeminiProperties,
    /** 시간 제한과 리다이렉트 금지가 설정된 Gemini 전용 HTTP 클라이언트다. */
    @param:Qualifier("geminiRestClient")
    private val restClient: RestClient,
    /** JSON 생성/읽기에 사용하되 앱 입력의 느슨한 타입 변환에 의존하지 않고 직접 검증한다. */
    private val objectMapper: ObjectMapper,
) : MusicRecommendationClient {
    /** 실제 요청은 한 번만 한다. 실패해도 무료 한도를 소모하는 자동 재시도나 다른 모델 호출은 하지 않는다. */
    override fun recommend(
        summary: RecommendationStepSummaryData,
        request: GenerateMusicRecommendationRequest,
    ): AiMusicRecommendation {
        if (!properties.enabled || properties.apiKey.isBlank()) {
            throw MusicRecommendationUnavailableException()
        }
        // 사용자 ID, 날짜, 닉네임, 소셜 계정, JWT, 원본 일별 기록은 외부로 보내지 않는다.
        val input = mapOf(
            "stepSummary" to summary,
            "preferredMoods" to request.preferredMoods.distinct(),
            "preferredGenres" to request.preferredGenres.distinct(),
            "durationMinutes" to request.durationMinutes,
        )
        val body = mapOf(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to GeminiRecommendationPrompt.instructions))),
            "contents" to listOf(
                mapOf("role" to "user", "parts" to listOf(mapOf("text" to objectMapper.writeValueAsString(input)))),
            ),
            "generationConfig" to mapOf(
                "responseMimeType" to "application/json",
                "responseJsonSchema" to GeminiRecommendationPrompt.responseSchema,
                "candidateCount" to 1,
                "maxOutputTokens" to properties.maxOutputTokens,
            ),
        )
        val response = callGemini(objectMapper.writeValueAsString(body))
        return parseRecommendation(extractAnswer(parseJson(response)))
    }

    /** HTTP 실패의 원문은 사용자 데이터/키가 포함될 수 있어 읽어 로그에 남기거나 응답으로 전달하지 않는다. */
    private fun callGemini(body: String): String {
        try {
            return restClient.post()
                .uri("/v1beta/models/{model}:generateContent", properties.model)
                // 쿼리 문자열에 비밀키를 넣지 않아 URL 로그에 노출되지 않게 한다.
                .header("x-goog-api-key", properties.apiKey.trim())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange { _, response ->
                    val status = response.statusCode.value()
                    if (status == 429) {
                        throw MusicRecommendationRateLimitException()
                    }
                    if (!response.statusCode.is2xxSuccessful) {
                        logger.warn("Gemini recommendation request failed (HTTP {})", status)
                        throw MusicRecommendationUnavailableException("Music recommendation is temporarily unavailable")
                    }
                    // 모델 출력 상한 외에도 수신 크기를 제한해 비정상적으로 큰 응답을 메모리에 모두 올리지 않는다.
                    val bytes = response.body.readNBytes(MAX_RESPONSE_BYTES + 1)
                    if (bytes.isEmpty() || bytes.size > MAX_RESPONSE_BYTES) {
                        throw MusicRecommendationInvalidResponseException()
                    }
                    bytes.toString(Charsets.UTF_8)
                }
        } catch (_: RestClientException) {
            throw MusicRecommendationUnavailableException("Music recommendation is temporarily unavailable")
        } catch (_: IOException) {
            throw MusicRecommendationUnavailableException("Music recommendation is temporarily unavailable")
        }
    }

    /** 문법 오류와 JSON 뒤의 추가 문장을 모두 거절한다. 파서 예외에 담긴 원본 AI 문장은 외부로 내보내지 않는다. */
    private fun parseJson(value: String): JsonNode = try {
        objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value)
    } catch (_: JacksonException) {
        throw MusicRecommendationInvalidResponseException()
    }

    /** 정상 종료된 단일 후보의 최종 텍스트만 읽는다. 안전 필터 차단/토큰 소진/생각용 part는 결과로 쓰지 않는다. */
    private fun extractAnswer(response: JsonNode): JsonNode {
        if (!response.path("promptFeedback").path("blockReason").isMissingNode) invalidResponse()
        val candidates = response.path("candidates")
        if (!candidates.isArray || candidates.size() != 1) invalidResponse()
        val candidate = candidates.path(0)
        if (candidate.path("finishReason").stringValue("") != "STOP") invalidResponse()
        val parts = candidate.path("content").path("parts")
        if (!parts.isArray) invalidResponse()
        val text = parts.values().filter { !it.path("thought").booleanValue(false) }
            .joinToString("") { part ->
                // JSON 본문에는 들여쓰기/줄바꿈이 있을 수 있고, part 경계의 공백도 임의로 지우지 않는다.
                val value = part.path("text")
                if (!value.isString) invalidResponse()
                value.stringValue()
            }
        if (text.isBlank()) invalidResponse()
        return parseJson(text)
    }

    /** 형식과 앱 계약을 재검증한다. AI가 돌려준 UUID/통계/검색어/추가 필드는 허용하지 않는다. */
    private fun parseRecommendation(node: JsonNode): AiMusicRecommendation {
        requireFields(node, setOf("activityLevel", "reason", "track"))
        val activity = enumValue<RecommendationActivityLevel>(node.path("activityLevel"))
        val reason = requiredText(node.path("reason"), 300)
        if (!KOREAN_PATTERN.containsMatchIn(reason) || !STEP_REASON_PATTERN.containsMatchIn(reason)) invalidResponse()
        val track = node.path("track")
        requireFields(track, setOf("title", "artist"))
        val title = requiredTrackText(track.path("title"))
        val artist = requiredTrackText(track.path("artist"))
        if (GENERIC_TRACK_PATTERN.containsMatchIn(title) || artist.equals("Various Artists", ignoreCase = true)) {
            invalidResponse()
        }
        return AiMusicRecommendation(activity, reason, title, artist)
    }

    /** 곡명/가수명에 URL이나 HTML이 섞이면 검색 문자열과 외부 Intent에 전달하지 않는다. */
    private fun requiredTrackText(node: JsonNode): String {
        val text = requiredText(node, 120)
        if (URL_PATTERN.containsMatchIn(text) || '<' in text || '>' in text) invalidResponse()
        return text
    }

    /** 다른 타입을 문자열로 자동 변환하지 않고 빈 값/길이 초과/제어 문자를 거절한다. */
    private fun requiredText(node: JsonNode, maxLength: Int): String {
        if (!node.isString) invalidResponse()
        val text = node.stringValue().trim()
        if (text.isEmpty() || text.length > maxLength || text.any { it.isISOControl() }) invalidResponse()
        return text
    }

    /** AI 응답 객체의 필수 필드 누락과 정의되지 않은 필드를 동시에 검사한다. */
    private fun requireFields(node: JsonNode, fields: Set<String>) {
        if (!node.isObject || node.propertyNames().toSet() != fields) invalidResponse()
    }

    /** 알 수 없는 enum이나 숫자 등 타입이 다른 값은 기본값으로 대체하지 않는다. */
    private inline fun <reified T : Enum<T>> enumValue(node: JsonNode): T =
        enumValues<T>().firstOrNull { it.name == requiredText(node, 40) } ?: invalidResponse()

    /** 검증 실패 시 내부 데이터가 없는 일정한 예외로 공통 502 응답을 만든다. */
    private fun invalidResponse(): Nothing = throw MusicRecommendationInvalidResponseException()

    private companion object {
        /** 짧은 추천 JSON에 충분한 최대 64KiB 수신 한도다. */
        const val MAX_RESPONSE_BYTES = 64 * 1024
        /** 앱이 검색 문자열을 URL로 오인하지 않도록 명시적인 링크 형태를 거절한다. */
        val URL_PATTERN = Regex("[a-z][a-z0-9+.-]*://|www\\.", RegexOption.IGNORE_CASE)
        /** 추천 이유가 한국어라는 앱 계약을 최소 한 글자의 한글 포함 여부로 검증한다. */
        val KOREAN_PATTERN = Regex("[가-힣]")
        /** 추천 이유가 단순 감상문이 아니라 걸음 통계를 근거로 설명하는지 확인한다. */
        val STEP_REASON_PATTERN = Regex("걸음|평균|활동량")
        /** 특정 곡 대신 플레이리스트·믹스·모음집을 제목처럼 반환하는 명백한 오류를 거절한다. */
        val GENERIC_TRACK_PATTERN = Regex("\\b(playlist|mix|compilation|station)\\b", RegexOption.IGNORE_CASE)
        /** 상태 코드만 기록하며 프롬프트/응답 본문/API 키는 기록하지 않는다. */
        val logger = LoggerFactory.getLogger(GeminiMusicRecommendationClient::class.java)
    }
}
