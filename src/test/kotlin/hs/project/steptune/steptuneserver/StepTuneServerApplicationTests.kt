package hs.project.steptune.steptuneserver

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

/** 필수 Bean, 설정값, Flyway 마이그레이션을 포함한 전체 Spring 서버가 시작되는지 확인한다. */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:step_tune_context;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.auth.google-client-id=test.apps.googleusercontent.com",
        "app.auth.jwt-secret=0123456789abcdef0123456789abcdef",
    ],
)
class StepTuneServerApplicationTests {

    /** 메서드 본문보다 Spring context 생성 자체가 성공하는지가 검증 대상이다. */
    @Test
    fun contextLoads() {
    }

}
