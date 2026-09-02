# Step Tune Server

Step Tune Android 앱의 Google, Kakao, Naver 로그인과 서버 세션을 담당하는 Kotlin/Spring Boot API입니다.

## 현재 구현된 인증 흐름

1. Android 앱이 소셜 SDK에서 토큰을 받습니다.
   - Google: ID 토큰
   - Kakao, Naver: 액세스 토큰
2. 앱이 제공자와 토큰을 `POST /api/v1/auth/social`로 보냅니다.
3. 서버가 제공자별 방식으로 토큰과 앱 일치 여부를 검증합니다.
   - Google: 서명, 만료 시간, 발급자, Web Client ID(`aud`)
   - Kakao: 액세스 토큰 정보와 Kakao App ID, 사용자 정보
   - Naver: 서버의 Client ID/Secret을 포함한 사용자 프로필 API
4. `(provider, provider subject)`로 기존 사용자를 찾고, 처음 로그인한 계정이면 랜덤 닉네임과 함께 사용자를 자동 생성합니다.
5. 서버가 15분짜리 Step Tune 액세스 JWT와 30일짜리 리프레시 토큰을 발급합니다.
6. 리프레시 토큰은 원문 대신 SHA-256 해시만 DB에 저장되며 갱신할 때마다 교체됩니다.

## 로컬 실행 준비

Java 21이 필요합니다. 이 PC에서는 Android Studio에 포함된 JBR 21을 사용할 수 있습니다.

IntelliJ IDEA의 서버 실행 구성에서 다음 환경변수를 설정합니다.

```text
GOOGLE_WEB_CLIENT_ID=발급받은-Web-Client-ID
KAKAO_APP_ID=숫자로-된-Kakao-App-ID
NAVER_CLIENT_ID=발급받은-Naver-Client-ID
NAVER_CLIENT_SECRET=발급받은-Naver-Client-Secret
JWT_SECRET=32바이트-이상의-랜덤한-비밀값
```

PowerShell에서 JWT 비밀값을 한 번 생성하려면 다음 명령을 사용할 수 있습니다.

```powershell
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
[Convert]::ToBase64String($bytes)
```

생성한 값과 Naver Client Secret은 서버 환경변수에만 저장하고 Git이나 Android 앱에 넣지 않습니다. Google OAuth Client Secret은 이 로그인 방식에서 사용하지 않습니다. Kakao와 Naver 설정값이 비어 있어도 Google 로그인 서버는 실행되지만, 해당 제공자의 로그인 요청은 HTTP `503`을 반환합니다.

로컬 DB는 PC에 MySQL을 직접 설치하지 않고 Docker Compose 컨테이너로 실행합니다. `docker` 명령이 없다면 [Docker Desktop for Windows 공식 안내](https://docs.docker.com/desktop/setup/install/windows-install/)에 따라 WSL 2 방식으로 설치합니다. Docker Desktop을 켠 다음 프로젝트 루트에서 다음 명령을 실행합니다.

```powershell
docker compose up -d
docker compose ps
```

`step-tune-mysql`이 `healthy`가 되면 서버를 실행합니다.

```powershell
.\gradlew.bat bootRun
```

서버가 처음 연결되면 Flyway가 `app_users`, `social_accounts`, `auth_sessions` 테이블과 인덱스를 자동으로 생성합니다. DB 데이터는 `step_tune_mysql_data` Docker named volume에 보존되므로 컨테이너를 중지하거나 다시 만들어도 유지됩니다.

```powershell
# DB 중지(데이터 유지)
docker compose stop

# 중지한 DB 다시 시작
docker compose start

# 컨테이너 제거(데이터 유지)
docker compose down
```

`docker compose down -v`는 named volume과 모든 로컬 DB 데이터를 삭제하므로 초기화가 정말 필요할 때만 사용합니다. 운영 환경에서는 `compose.yaml`의 로컬 비밀번호를 사용하지 않고 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 배포 서비스의 비밀 환경변수로 지정합니다.

## API

### 소셜 로그인

Android 앱에서는 세 제공자 모두 공통 API를 사용합니다.

```http
POST /api/v1/auth/social
Content-Type: application/json

{
  "provider": "kakao",
  "token": "Kakao access token"
}
```

`provider`는 `google`, `kakao`, `naver` 중 하나이며 대소문자를 구분하지 않습니다. `google`에는 ID 토큰을, `kakao`와 `naver`에는 액세스 토큰을 전달합니다. `providerId`는 요청으로 받지 않고 서버가 검증된 토큰에서 직접 추출합니다. Android 전용 API이므로 `deviceType`과 `deviceFingerprint`도 받지 않습니다.

기존 Google 전용 API도 호환을 위해 유지됩니다.

```http
POST /api/v1/auth/google
Content-Type: application/json

{
  "idToken": "Google ID Token"
}
```

성공 응답:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "accessToken": "Step Tune JWT",
    "accessTokenExpiresIn": 900,
    "refreshToken": "opaque refresh token",
    "userData": {
      "userId": 1,
      "nickName": "스텝러너12345678"
    }
  }
}
```

`userId`는 문자열 UUID가 아니라 JSON 숫자입니다. Android 응답 DTO에서도 `String`이 아닌 `Long`으로 선언합니다. 최초 사용자는 `1`, 다음 신규 사용자는 `2`, `3` 순서로 발급됩니다.

`accessTokenExpiresIn`은 액세스 토큰의 남은 유효 시간을 초 단위로 나타냅니다. `900`은 15분입니다. 신규 사용자는 `스텝러너`와 8자리 숫자를 조합한 중복 없는 닉네임을 자동으로 받고, 추후 프로필 API에서 변경할 수 있도록 확장합니다.

동일한 이메일이라도 서로 다른 소셜 제공자의 계정은 자동으로 합치지 않습니다. 이메일만으로 계정을 병합하면 다른 사람의 계정이 잘못 연결될 위험이 있기 때문입니다. 추후 여러 소셜 계정을 하나의 Step Tune 사용자에 연결하려면 로그인된 상태에서 별도의 계정 연결 API를 구현해야 합니다.

### 액세스 토큰 갱신

```http
POST /api/v1/auth/refresh
Content-Type: application/json

{
  "refreshToken": "저장해 둔 리프레시 토큰"
}
```

로그인 성공 응답의 `data`와 같은 구조로 새 액세스 토큰, 새 리프레시 토큰, 사용자 정보가 반환됩니다. 앱은 두 토큰을 모두 교체하고 요청에 사용한 이전 리프레시 토큰은 즉시 폐기해야 합니다. 앱 시작 시 저장된 리프레시 토큰으로 이 API를 호출하면 자동 로그인할 수 있습니다.

### 로그아웃

```http
POST /api/v1/auth/logout
Content-Type: application/json

{
  "refreshToken": "현재 리프레시 토큰"
}
```

성공하면 다음 응답을 반환하며, 앱은 로컬에 저장한 토큰과 사용자 캐시를 삭제합니다.

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

### 내 사용자 정보

```http
GET /api/v1/me/profile
Authorization: Bearer Step-Tune-Access-Token
```

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "userId": 1,
    "nickName": "스텝러너12345678"
  }
}
```

### 닉네임 중복 확인

프로필 저장 버튼을 누르기 전에 입력한 닉네임을 사용할 수 있는지 확인합니다. `nickName`은 URL
쿼리 값으로 전달하고 Access Token이 필요합니다. 서버는 앞뒤 공백을 제거한 값을 반환합니다.

```http
GET /api/v1/me/nickname/availability?nickName=새닉네임
Authorization: Bearer Step-Tune-Access-Token
```

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "nickName": "새닉네임",
    "available": true
  }
}
```

중복 확인 결과는 닉네임을 예약하지 않습니다. 다른 사용자가 동시에 같은 값을 저장할 수 있으므로
Android는 실제 변경 API에서 `409 Conflict`가 오는 경우도 중복 안내로 처리해야 합니다.

### 닉네임 변경

닉네임은 앞뒤 공백을 제외하고 1~30자여야 합니다.

```http
PATCH /api/v1/me/nickname
Authorization: Bearer Step-Tune-Access-Token
Content-Type: application/json

{
  "nickName": "새닉네임"
}
```

성공하면 `GET /api/v1/me/profile`과 같은 사용자 정보가 반환됩니다. 다른 사용자가 이미 사용 중이면 다음처럼
`409`가 반환됩니다.

```json
{
  "code": 409,
  "message": "Nickname is already in use",
  "data": null
}
```

### 회원 탈퇴

```http
DELETE /api/v1/me/account
Authorization: Bearer Step-Tune-Access-Token
```

성공 응답은 `{"code":200,"message":"success","data":null}`입니다. 사용자 레코드를 삭제하면
연결된 Google·Kakao·Naver 계정과 모든 Refresh Token 세션도 DB 외래키 규칙으로 함께 삭제됩니다.
Android는 성공 직후 저장한 Access/Refresh Token과 사용자 캐시를 모두 지우고 로그인 화면으로 이동해야
합니다. `daily_step_records`도 DB 외래키 규칙으로 함께 삭제됩니다. 기존 Access Token의 서명 만료 시간이
남아 있어도 사용자 조회가 필요한 API에서는 사용할 수 없습니다.

### 일별 걸음 동기화

Android가 보관한 하루 총걸음을 서버에 신규 저장하거나 같은 날짜의 최신 값으로 갱신합니다. `userId`는
요청에 넣지 않습니다. 서버가 Bearer Access Token에서 현재 사용자를 확인하므로 다른 사용자의 기록을
저장할 수 없습니다. 여러 날의 로컬 기록을 한 번에 올릴 수 있으며 요청 한 번의 최대 범위는 366건입니다.

```http
PUT /api/v1/steps/daily-records/sync
Authorization: Bearer Step-Tune-Access-Token
Content-Type: application/json

{
  "records": [
    {
      "recordDate": "2026-09-01",
      "stepCount": 8432,
      "measuredAt": "2026-09-01T23:55:00+09:00"
    },
    {
      "recordDate": "2026-09-02",
      "stepCount": 2190,
      "measuredAt": "2026-09-02T15:30:00+09:00"
    }
  ]
}
```

`recordDate`는 사용자의 현지 날짜, `stepCount`는 그 날짜의 누적 총걸음, `measuredAt`은 앱이 마지막으로
측정한 시각입니다. `measuredAt`에는 `Z` 또는 `+09:00` 같은 시간대 오프셋을 반드시 포함합니다. 같은
사용자와 날짜를 다시 전송하면 걸음 수를 더하지 않고 기존 총합을 교체하므로 네트워크 재시도에도 중복
행이 생기지 않습니다.

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "records": [
      {
        "recordDate": "2026-09-01",
        "stepCount": 8432,
        "measuredAt": "2026-09-01T14:55:00Z",
        "updatedAt": "2026-09-02T06:30:00Z"
      }
    ],
    "syncTime": "2026-09-02T06:30:00Z"
  }
}
```

한 요청 안에 같은 날짜가 두 번 있거나 걸음 수가 음수이면 `400 Bad Request`입니다.

### 특정 날짜 걸음 조회

```http
GET /api/v1/steps/daily-records/by-date?recordDate=2026-09-02
Authorization: Bearer Step-Tune-Access-Token
```

저장된 날짜이면 `data.record`에 일별 기록이 들어갑니다. 아직 저장하지 않은 날짜는 정상 응답
`{"code":200,"message":"success","data":{"record":null}}`로 반환되어, 실제 0걸음을 저장한 기록과
구분할 수 있습니다.

### 걸음 이력 조회

```http
GET /api/v1/steps/daily-records/history?from=2026-09-01&to=2026-09-30
Authorization: Bearer Step-Tune-Access-Token
```

`from`, `to` 날짜를 모두 포함하며 기록이 있는 날짜만 오름차순으로 반환합니다. 한 번에 최대 366일을
조회할 수 있습니다. 더 긴 이력이 필요하면 Android가 구간을 나눠 호출합니다.

## 검증

```powershell
.\gradlew.bat test
```

Windows 사용자 경로에 한글이 포함된 환경에서 Gradle 9.5.1 테스트 실행기의 Java 인자 파일이 깨지면, 프로젝트와 Gradle 캐시를 영문 경로로 옮기거나 임시 ASCII 드라이브 경로에서 테스트를 실행해야 할 수 있습니다.
