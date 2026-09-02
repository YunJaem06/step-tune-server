package hs.project.steptune.steptuneserver.step

import hs.project.steptune.steptuneserver.auth.requireUserId
import hs.project.steptune.steptuneserver.common.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Android 앱의 일별 걸음 저장과 조회 HTTP API를 정의한다. */
@RestController
@RequestMapping("/api/v1/steps/daily-records")
class StepRecordController(
    /** Controller가 DB를 직접 다루지 않고 걸음 업무 규칙을 위임하는 Service다. */
    private val stepRecordService: StepRecordService,
) {
    /** 여러 날짜의 하루 총걸음을 신규 생성 또는 갱신한다. */
    @PutMapping("/sync")
    fun syncDailyRecords(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: SyncDailyStepRecordsRequest,
    ): ApiResponse<DailyStepRecordSyncData> =
        ApiResponse.success(stepRecordService.syncDailyRecords(jwt.requireUserId(), request))

    /** 특정 날짜의 현재 사용자 걸음 기록 한 건을 조회한다. */
    @GetMapping("/by-date")
    fun getDailyRecord(
        @AuthenticationPrincipal jwt: Jwt,
        // 빈 값도 Service의 날짜 검증을 거쳐 공통 400 JSON이 되도록 기본값을 지정한다.
        @RequestParam(defaultValue = "") recordDate: String,
    ): ApiResponse<DailyStepRecordLookupData> =
        ApiResponse.success(stepRecordService.getDailyRecord(jwt.requireUserId(), recordDate))

    /** from과 to를 모두 포함한 기간의 현재 사용자 걸음 기록을 날짜순으로 조회한다. */
    @GetMapping("/history")
    fun getHistory(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "") from: String,
        @RequestParam(defaultValue = "") to: String,
    ): ApiResponse<DailyStepRecordHistoryData> =
        ApiResponse.success(stepRecordService.getHistory(jwt.requireUserId(), from, to))
}

