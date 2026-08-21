package hs.project.steptune.steptuneserver.common

/**
 * Android 앱이 모든 API 결과를 같은 방식으로 파싱하도록 만든 공통 응답 포맷이다.
 * Ranking App과 동일하게 code, message, data 세 필드를 사용한다.
 */
data class ApiResponse<T>(
    /** 성공은 200, 실패는 실제 HTTP 상태 코드와 같은 숫자를 사용한다. */
    val code: Int,
    /** 성공 시 success, 실패 시 사용자 또는 개발자가 원인을 알 수 있는 설명이다. */
    val message: String,
    /** API별 실제 결과이며 결과가 필요 없는 성공이나 오류에서는 null이다. */
    val data: T?,
) {
    companion object {
        /** 결과 데이터가 있는 표준 성공 응답을 만든다. */
        fun <T> success(data: T): ApiResponse<T> = ApiResponse(
            code = 200,
            message = "success",
            data = data,
        )

        /** 로그아웃처럼 반환할 업무 데이터가 없는 표준 성공 응답을 만든다. */
        fun successWithoutData(): ApiResponse<Any> = ApiResponse(
            code = 200,
            message = "success",
            data = null,
        )
    }
}
