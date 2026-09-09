package hs.project.steptune.steptuneserver.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/** 기존 RestClient 의존성을 재사용하므로 별도의 AI SDK나 Python 서버를 설치하지 않는다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeminiProperties::class)
class GeminiConfig {
    /** Gemini 전용 연결/응답 제한을 설정한다. 리다이렉트로 API 키가 다른 호스트에 전달되지 않게 한다. */
    @Bean
    fun geminiRestClient(builder: RestClient.Builder, properties: GeminiProperties): RestClient {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds.toLong()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds.toLong()))
        }
        return builder.clone()
            .baseUrl("https://generativelanguage.googleapis.com")
            .requestFactory(requestFactory)
            .build()
    }
}
