package hs.project.steptune.steptuneserver

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Step Tune 서버의 시작점이다.
 *
 * [SpringBootApplication]은 현재 패키지 아래의 Controller, Service, Repository,
 * Configuration 등을 찾아 Spring 객체(Bean)로 등록하고 자동 설정을 적용한다.
 */
@SpringBootApplication
class StepTuneServerApplication

/**
 * JVM이 가장 먼저 실행하는 함수다.
 * Spring 애플리케이션 컨텍스트를 만들고 내장 웹 서버를 시작한다.
 */
fun main(args: Array<String>) {
    runApplication<StepTuneServerApplication>(*args)
}
