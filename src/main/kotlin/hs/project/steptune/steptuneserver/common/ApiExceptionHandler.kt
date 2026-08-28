package hs.project.steptune.steptuneserver.common

import hs.project.steptune.steptuneserver.auth.InvalidAuthTokenException
import hs.project.steptune.steptuneserver.auth.SocialProviderNotConfiguredException
import hs.project.steptune.steptuneserver.auth.SocialVerificationUnavailableException
import hs.project.steptune.steptuneserver.auth.UserNotFoundException
import hs.project.steptune.steptuneserver.user.InvalidNicknameException
import hs.project.steptune.steptuneserver.user.NicknameAlreadyExistsException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Controller/Service에서 발생한 예외를 일관된 HTTP 상태와 [ApiResponse] JSON으로 바꾼다.
 * 내부 스택 트레이스를 앱에 노출하지 않고, 예외 종류별로 필요한 메시지만 반환한다.
 */
@RestControllerAdvice
class ApiExceptionHandler {
    /** 위조·만료·폐기된 인증 토큰은 401 Unauthorized로 응답한다. */
    @ExceptionHandler(InvalidAuthTokenException::class)
    fun handleInvalidAuthToken(exception: InvalidAuthTokenException): ResponseEntity<ApiResponse<Nothing>> =
        error(HttpStatus.UNAUTHORIZED, exception.message ?: "Authentication failed")

    /** 소셜 제공자 API나 네트워크 장애는 앱이 나중에 재시도할 수 있도록 503으로 응답한다. */
    @ExceptionHandler(SocialVerificationUnavailableException::class)
    fun handleSocialUnavailable(exception: SocialVerificationUnavailableException): ResponseEntity<ApiResponse<Nothing>> =
        error(HttpStatus.SERVICE_UNAVAILABLE, "${exception.provider} login is temporarily unavailable")

    /** 필요한 Client ID/Secret 등이 없는 서버 설정 문제도 503으로 응답한다. */
    @ExceptionHandler(SocialProviderNotConfiguredException::class)
    fun handleSocialNotConfigured(exception: SocialProviderNotConfiguredException): ResponseEntity<ApiResponse<Nothing>> =
        error(HttpStatus.SERVICE_UNAVAILABLE, "${exception.provider} login is not configured")

    /** JWT의 사용자와 실제 DB 상태가 맞지 않으면 404로 응답한다. */
    @ExceptionHandler(UserNotFoundException::class)
    fun handleUserNotFound(): ResponseEntity<ApiResponse<Nothing>> =
        error(HttpStatus.NOT_FOUND, "User not found")

    /** 공백 또는 30자 초과 닉네임은 앱이 입력값을 고쳐 다시 보낼 수 있도록 400으로 응답한다. */
    @ExceptionHandler(InvalidNicknameException::class)
    fun handleInvalidNickname(exception: InvalidNicknameException): ResponseEntity<ApiResponse<Nothing>> =
        error(HttpStatus.BAD_REQUEST, exception.message ?: "Nickname is invalid")

    /** 다른 사용자가 이미 쓰는 닉네임은 현재 상태와 충돌하므로 409 Conflict로 응답한다. */
    @ExceptionHandler(NicknameAlreadyExistsException::class)
    fun handleNicknameAlreadyExists(exception: NicknameAlreadyExistsException): ResponseEntity<ApiResponse<Nothing>> =
        error(HttpStatus.CONFLICT, exception.message ?: "Nickname is already in use")

    /** @Valid와 Bean Validation에서 잡힌 빈 필드 등은 첫 번째 오류를 400으로 반환한다. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(exception: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Nothing>> {
        val message = exception.bindingResult.fieldErrors.firstOrNull()?.let { error ->
            "${error.field}: ${error.defaultMessage}"
        } ?: "Request validation failed"
        return error(HttpStatus.BAD_REQUEST, message)
    }

    /** JSON 문법, enum 값, 타입이 잘못되어 DTO로 변환할 수 없는 요청은 400으로 반환한다. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableRequest(): ResponseEntity<ApiResponse<Nothing>> =
        error(HttpStatus.BAD_REQUEST, "Request body is invalid")

    /** 오류 응답도 성공 응답과 같은 code/message/data 외형으로 만드는 내부 도우미다. */
    private fun error(status: HttpStatus, message: String): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(status).body(
            ApiResponse(
                code = status.value(),
                message = message,
                data = null,
            ),
        )
}
