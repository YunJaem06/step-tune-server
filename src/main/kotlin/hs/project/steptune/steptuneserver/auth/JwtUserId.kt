package hs.project.steptune.steptuneserver.auth

import org.springframework.security.oauth2.jwt.Jwt

/**
 * 검증된 Step Tune Access Token의 subject를 숫자형 내부 사용자 ID로 변환한다.
 * 여러 보호 API가 동일한 인증 해석 규칙을 사용하도록 공통 확장 함수로 둔다.
 */
fun Jwt.requireUserId(): Long =
    subject?.toLongOrNull()
        ?: throw InvalidAuthTokenException("Access token subject is invalid")

