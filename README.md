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
사용자와 날짜를 다시 전송하면 걸음 수를 더하지 않고 기존 값과 요청값 중 큰 총합을 유지합니다. 따라서
네트워크 재시도에 중복 행이 생기지 않고 늦게 도착한 오래된 요청도 서버 걸음 수를 감소시키지 않습니다.

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

### 최근 7일 걸음 통계

AI 음악 추천과 Android 통계 화면에서 사용할 로그인 사용자의 기준일 포함 최근 7일 통계를 반환합니다.
서버에 실제 기록이 있는 날짜만 평균에 포함하므로 아직 동기화되지 않은 날짜를 0걸음으로 잘못 계산하지
않습니다.

```http
GET /api/v1/steps/statistics/weekly?recordDate=2026-09-03
Authorization: Bearer Step-Tune-Access-Token
```

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "recordDate": "2026-09-03",
    "todayStepCount": 5000,
    "recent7DayAverage": 3000.00,
    "recordedDayCount": 3,
    "differenceFromAverage": 2000.00,
    "changeRatePercent": 66.67
  }
}
```

`recent7DayAverage`는 기준일과 이전 6일 중 기록이 있는 날짜의 평균이고 `recordedDayCount`는 그 계산에
사용한 날짜 수입니다. `differenceFromAverage`는 기준일 걸음에서 평균을 뺀 값이며 음수일 수도 있습니다.
평균이 0이면 0으로 나눌 수 없으므로 `changeRatePercent`는 `null`입니다. 기준일 기록이 아직 없으면
Android가 걸음을 먼저 동기화할 수 있도록 `404 Not Found`를 반환합니다.

### AI 음악 추천 생성 (Gemini)

Android는 걸음 동기화가 끝난 뒤 원하는 분위기, 장르, 재생 시간을 선택해 음악 추천을 요청합니다.
`userId`와 `stepCount`는 요청에 넣지 않고 서버가 Access Token과 저장된 걸음 기록에서 확인합니다.

```http
POST /api/v1/music-recommendations/generate
Authorization: Bearer Step-Tune-Access-Token
Content-Type: application/json

{
  "recordDate": "2026-09-03",
  "preferredMoods": ["ENERGETIC", "LIVELY"],
  "preferredGenres": ["HIP_HOP", "RNB"],
  "durationMinutes": 30
}
```

`preferredMoods`는 Android와 같은 `CALM`, `ENERGETIC`, `EMOTIONAL`, `FOCUSED`, `LIVELY` 중
최대 2개입니다. `preferredGenres`는 `BALLAD`, `HIP_HOP`, `RNB`, `POP`, `ROCK`, `INDIE`,
`JAZZ`, `CLASSICAL` 중 최대 3개이고, 재생 시간은 10~120분입니다. 두 목록은 생략할 수
있으며 enum 값은 기존 서버 설정에 따라 대소문자를 구분하지 않습니다.

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "recommendationId": "a03f850b-84df-4c80-bf0e-f37489f69665",
    "recordDate": "2026-09-03",
    "stepSummary": {
      "todayStepCount": 5000,
      "recent7DayAverage": 3000.00,
      "recordedDayCount": 3,
      "differenceFromAverage": 2000.00,
      "changeRatePercent": 66.67
    },
    "activityLevel": "HIGH",
    "durationMinutes": 30,
    "reason": "오늘 걸음이 최근 평균보다 많고 활기찬 팝을 선호해 BTS의 Dynamite를 추천했어요.",
    "track": {
      "title": "Dynamite",
      "artist": "BTS",
      "searchQuery": "BTS Dynamite official audio"
    },
    "generatedAt": "2026-09-03T06:30:00Z"
  }
}
```

`recommendationId`는 서버 DB의 숫자 PK가 아니라 요청마다 발급하는 UUID 문자열입니다. 서버는 추천 결과를
저장하지 않으며 Android가 응답을 Room에 보관합니다. `activityLevel`은 `LOW`, `MODERATE`, `HIGH` 중 하나이고,
`track`에는 Gemini가 고른 정확히 한 곡만 들어갑니다. `searchQuery`는 Gemini의 자유 형식 문장이 아니라 서버가
`artist + title + official audio`로 조립하므로 Android는 이 값을 URL 인코딩해 YouTube 검색에 사용합니다.

기준 날짜의 걸음 기록이 없으면 `404 Not Found`입니다. 실제 추천은 Gemini가 생성하며 별도의 음악 검색 API는
호출하지 않습니다. 따라서 Gemini에 실제 발매곡만 고르도록 지시하지만 곡 존재를 100% 검증하려면 추후 음악
카탈로그 API가 필요합니다. Android는 응답의 검색어로 YouTube 검색 화면을 열고 결과 기록을 Room에 보관합니다.

#### 무료 등급 연결 준비

1. [Google AI Studio](https://aistudio.google.com/)에 로그인하고 무료 테스트용 프로젝트/API 키를 만듭니다.
2. 해당 프로젝트의 Billing Tier가 **Free Tier**인지 확인합니다. 무료로만 테스트하려면 결제 계정을 연결하거나
   `Set up billing`/유료 업그레이드를 진행하지 않습니다. Google 로그인용 OAuth Client ID와 Gemini API 키는 별개입니다.
3. IntelliJ `Run → Edit Configurations → StepTuneServerApplication → Environment variables`에 아래 값을 추가합니다.
   각 항목의 이름은 Name, 값은 같은 행의 Value 칸에 입력하며 따옴표는 넣지 않습니다.

```text
GEMINI_ENABLED=true
GEMINI_API_KEY=AI-Studio에서-발급한-서버용-키
GEMINI_MODEL=gemini-3.5-flash-lite
```

4. 서버를 재시작하고, 테스트 계정의 걸음을 먼저 동기화한 뒤 추천 API를 요청합니다.
5. 테스트가 끝나면 `GEMINI_ENABLED=false`로 변경하고 재시작하면 추천의 외부 호출을 중단할 수 있습니다.

`GEMINI_ENABLED` 기본값은 `false`입니다. 키가 없거나 비활성 상태여도 로그인/걸음 API는 실행되고 추천만 503을
반환합니다. 모델 이름은 환경변수로 바꿀 수 있지만 모델별 지원/무료 할당량은 AI Studio에서 확인해야 합니다.
2026-09-04 확인 기준 기본 모델은 무료 등급을 지원합니다. 이 서버 설정 자체가 무료 과금 상태를 보장하지는 않습니다.
무료 한도/모델 제공 정책은 바뀔 수 있습니다.

- [Gemini 공식 가격](https://ai.google.dev/gemini-api/docs/pricing)
- [무료/유료 등급 안내](https://ai.google.dev/gemini-api/docs/billing)
- [계정별 호출 한도](https://ai.google.dev/gemini-api/docs/rate-limits)
- [JSON 구조화 출력](https://ai.google.dev/gemini-api/docs/structured-output)

#### 처리 구조와 안전장치

- `MusicRecommendationService`가 JWT 사용자 ID로 통계를 읽습니다. `StepStatisticsService`의 읽기 트랜잭션은
  조회 후 종료되며 Gemini 응답을 기다리는 동안 DB 커넥션을 점유하지 않습니다.
- `GeminiMusicRecommendationClient`가 집계 통계, 선호 분위기/장르, 희망 시간을 한 번 전송합니다.
  사용자 ID, 날짜, 닉네임, 이메일, JWT, 원본 일별 기록은 전송하지 않습니다.
- Gemini는 활동 수준, 실제 발매된 한 곡의 제목·가수, 걸음 통계와 선호를 반영한 짧은 한국어 설명만 생성합니다.
  플레이리스트·믹스·여러 후보·URL은 요청하지 않으며 걸음으로 실제 감정이나 건강을 단정하지 않게 지시합니다.
- JSON Schema와 서버 검증을 함께 사용합니다. enum/필수 필드/한 곡 구조/문자열 길이/URL·HTML 여부를 검사합니다.
  YouTube 검색어는 검증된 가수와 곡명으로 서버가 조립합니다. 곡의 실제 발매 여부와 음악적 품질까지 Gemini만으로
  완전히 보장할 수는 없습니다.
- UUID, 기준일, 통계, 희망 시간, 생성 시각은 서버가 직접 조립합니다. 추천용 Entity/Repository/DB 테이블은 없습니다.
- 연결 제한은 5초, 응답 대기는 기본 30초(`GEMINI_READ_TIMEOUT_SECONDS`로 변경), 출력 상한은 2,048토큰,
  HTTP 응답 상한은 64KiB입니다. 자동 재시도/유료 모델 대체/웹 검색 도구는 사용하지 않습니다.
- API 키는 요청 헤더로만 전송합니다. 키와 프롬프트/응답 본문은 앱 응답이나 서버 로그에 출력하지 않습니다.
  키는 Git/Android/채팅에 넣지 말고 서버 환경변수에만 보관합니다.

무료 Gemini 서비스는 입력/응답이 제품 개선에 사용되거나 검토될 수 있습니다. 처음에는 합성 테스트 기록을 사용하고,
실제 개인정보/민감정보를 전송하지 마세요. 식별자를 빼는 것만으로 모든 데이터가 완전히 익명화되는 것은 아닙니다.
[Gemini 데이터 처리 약관](https://ai.google.dev/gemini-api/terms)을 확인한 뒤 실제 데이터로 테스트하세요.

| HTTP 상태 | 의미 | Android 처리 |
| --- | --- | --- |
| 200 | 검증된 한 곡 추천 생성 성공 | Room 저장 및 YouTube 검색 화면 연결 |
| 400 | 잘못된 요청 조건 | 입력값 수정 |
| 401 | Step Tune 인증 실패 | 기존 토큰 갱신/로그인 처리 |
| 404 | 사용자 또는 기준일 걸음 없음 | 걸음 동기화/계정 상태 확인 |
| 429 | Gemini 호출 한도 초과 | 반복 호출하지 말고 나중에 다시 시도 |
| 502 | 잘린 응답/차단/잘못된 추천 JSON | 안내 후 사용자가 다시 시도 |
| 503 | AI 비활성/키 누락/외부 연결·설정 문제 | 서버 설정 확인; 자동 로그아웃하지 않음 |

Google의 키 오류(401/403)는 Step Tune 토큰 오류가 아니므로 앱에는 503으로 변환합니다.
실제 AI 호출 테스트는 키 발급과 Free Tier 확인 후에 별도로 진행해야 합니다.

## 검증

```powershell
.\gradlew.bat test
```

Gemini HTTP/서비스/계약 테스트는 MockRestServiceServer와 가짜 AI 응답, H2만 사용합니다.
테스트 실행에는 실제 Gemini 키가 필요 없으며 외부 AI 호출/과금이 발생하지 않습니다.

Windows 사용자 경로에 한글이 포함된 환경에서 Gradle 9.5.1 테스트 실행기의 Java 인자 파일이 깨지면, 프로젝트와 Gradle 캐시를 영문 경로로 옮기거나 임시 ASCII 드라이브 경로에서 테스트를 실행해야 할 수 있습니다.
