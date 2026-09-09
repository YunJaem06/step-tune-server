package hs.project.steptune.steptuneserver.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/** Gemini 연결 설정이다. 키가 없어도 인증/걸음 서버는 실행되며, 추천을 요청할 때만 설정을 검사한다. */
@Validated
@ConfigurationProperties("app.gemini")
class GeminiProperties(
    /** 개발 중 실수로 외부 AI를 호출하지 않도록 명시적으로 켜야 한다. 무료 등급 여부를 보장하는 옵션은 아니다. */
    val enabled: Boolean = false,
    /** 서버 환경변수에만 보관한다. data class를 쓰지 않아 기본 toString에 키가 포함되지 않는다. */
    val apiKey: String = "",
    /** URL 경로나 쿼리를 삽입할 수 없도록 모델 이름만 허용한다. */
    @field:Pattern(regexp = "gemini-[a-zA-Z0-9.-]+")
    val model: String = "gemini-3.5-flash-lite",
    /** Google 서버에 연결하는 최대 대기 시간(초)이다. */
    @field:Min(1)
    @field:Max(30)
    val connectTimeoutSeconds: Int = 5,
    /** 응답 대기를 제한해 추천 요청이 서버 스레드를 계속 점유하지 않게 한다. */
    @field:Min(1)
    @field:Max(120)
    val readTimeoutSeconds: Int = 30,
    /** 출력(모델의 thinking 포함) 토큰 상한이다. 잘린 응답은 성공으로 반환하지 않는다. */
    @field:Min(512)
    @field:Max(4096)
    val maxOutputTokens: Int = 2048,
)
