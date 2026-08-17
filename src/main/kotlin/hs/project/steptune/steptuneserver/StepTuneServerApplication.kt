package hs.project.steptune.steptuneserver

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class StepTuneServerApplication

fun main(args: Array<String>) {
    runApplication<StepTuneServerApplication>(*args)
}
