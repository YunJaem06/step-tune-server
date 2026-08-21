plugins {
    // Kotlin 소스 코드를 JVM 바이트코드로 컴파일한다.
    kotlin("jvm") version "2.3.21"
    // final이 기본인 Kotlin 클래스를 Spring 프록시가 상속할 수 있게 자동으로 열어준다.
    kotlin("plugin.spring") version "2.3.21"
    // 기본 생성자 등이 필요한 JPA Entity를 Kotlin에서 자연스럽게 사용할 수 있게 한다.
    kotlin("plugin.jpa") version "2.3.21"
    // 실행 가능한 Spring Boot 애플리케이션과 bootRun/bootJar 작업을 제공한다.
    id("org.springframework.boot") version "4.1.0"
    // Spring Boot가 검증한 라이브러리 버전 조합을 자동으로 맞춘다.
    id("io.spring.dependency-management") version "1.1.7"
}

// 빌드 산출물을 식별하는 Maven 좌표와 현재 개발 버전이다.
group = "hs.project.steptune"
version = "0.0.1-SNAPSHOT"
description = "step-tune-server"

java {
    toolchain {
        // 개발 PC의 기본 Java와 무관하게 이 프로젝트는 Java 21로 빌드한다.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    // 아래 의존성을 Maven Central 공개 저장소에서 내려받는다.
    mavenCentral()
}

dependencies {
    // 서버 상태 확인(/actuator/health) 같은 운영 엔드포인트를 제공한다.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Entity/Repository와 트랜잭션 기반 DB 접근을 제공한다.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // 버전이 붙은 SQL로 DB 변경 이력을 관리한다.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    // Bearer JWT를 검증해 보호 API의 사용자를 인증한다.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // Kakao/Naver 외부 인증 API를 호출하는 RestClient를 제공한다.
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    // URL 접근 제어와 인증 필터 체인을 제공한다.
    implementation("org.springframework.boot:spring-boot-starter-security")
    // @Valid, @NotBlank 같은 요청값 검증을 제공한다.
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Controller, JSON 변환, 내장 웹 서버 등 REST API 기반을 제공한다.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // 로컬 및 배포용 MySQL에서 Flyway가 DB 종류를 인식하도록 한다.
    implementation("org.flywaydb:flyway-mysql")
    // Google ID Token의 공개키 서명과 audience를 검증한다.
    implementation("com.google.api-client:google-api-client:2.9.0")
    // Spring/JPA가 Kotlin 클래스와 프로퍼티 정보를 읽는 데 사용한다.
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // Kotlin data class와 Jackson JSON 변환을 자연스럽게 연결한다.
    implementation("tools.jackson.module:jackson-module-kotlin")

    // 로컬 Compose와 배포 환경의 MySQL에 접속하는 공식 JDBC 드라이버다.
    runtimeOnly("com.mysql:mysql-connector-j")

    // Actuator 상태 응답을 Spring 테스트 환경에서 검증한다.
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    // Repository, Entity, 트랜잭션을 포함한 JPA 통합 테스트 도구다.
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    // 인증 필터와 보호 API 동작을 테스트할 Spring Boot 보안 도구다.
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    // 요청 DTO의 Bean Validation을 테스트 환경에서도 활성화한다.
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    // 실제 포트를 열지 않고 MockMvc로 Controller HTTP 계약을 검증한다.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // 인증 사용자/JWT 관련 테스트 편의 기능을 제공한다.
    testImplementation("org.springframework.security:spring-security-test")
    // Kotlin assertEquals/assertFailsWith를 JUnit 5 위에서 사용한다.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // 자동 테스트는 별도의 Docker DB 없이 독립적으로 실행되도록 인메모리 H2를 사용한다.
    testRuntimeOnly("com.h2database:h2")
    // Gradle이 JUnit 5 테스트 엔진을 찾아 실행하도록 런처를 제공한다.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        // Java nullability를 엄격히 해석하고 생성자 애너테이션의 기본 대상을 명확히 한다.
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    // 모든 Gradle Test 작업이 JUnit Platform(JUnit 5)을 사용하게 한다.
    useJUnitPlatform()
}
