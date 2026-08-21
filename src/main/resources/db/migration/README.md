# 인증 DB 마이그레이션 설명

현재 `V1`~`V4`는 첫 MySQL 실행 전에 MySQL 8.4 문법으로 확정한 초기 이력입니다.
Flyway는 적용한 SQL 파일의 체크섬을 DB에 저장하므로, MySQL에 한 번 적용한 뒤에는 주석을
포함해 기존 파일을 수정하지 않습니다. 이후 구조 변경은 항상 다음 번호의 SQL 파일로 추가합니다.

## V1__create_auth_tables.sql

- `app_users`: Step Tune 내부 사용자와 생성/로그인 시각을 저장합니다.
- `social_accounts`: 검증된 `(provider, provider_subject)`를 내부 사용자에 연결합니다.
- `auth_sessions`: Refresh Token 원문 대신 SHA-256 해시, 만료 및 폐기 상태를 저장합니다.
- 외래 키의 `ON DELETE CASCADE`는 사용자를 삭제할 때 연결된 소셜 계정과 세션도 함께 정리합니다.
- 조회에 자주 쓰는 사용자 외래 키와 Refresh Token 해시에 인덱스/유니크 제약을 둡니다.
- MySQL에는 UUID 전용 타입이 없으므로 UUID를 `CHAR(36)`으로 저장합니다.
- 애플리케이션에서 UTC로 맞춘 시각을 마이크로초 정밀도의 `TIMESTAMP(6)`에 저장합니다.

## V2__add_provider_to_auth_sessions.sql

- 자동 로그인으로 토큰을 재발급할 때 최초 로그인 제공자를 유지하도록 세션에 `provider`를 추가합니다.
- V2 이전 세션은 Google 전용이었으므로 기존 행을 `GOOGLE`로 채운 뒤 `NOT NULL`로 변경합니다.

## V3__remove_device_name_from_auth_sessions.sql

- 현재 앱에서 사용하지 않는 `device_name`을 제거해 로그인 요청과 세션 구조를 단순화합니다.

## V4__use_random_nickname_and_remove_profile_image.sql

- `display_name`을 앱에서 실제 사용하는 `nickname`으로 변경합니다.
- 기존 사용자는 UUID 일부를 이용한 중복 가능성이 낮은 초기 닉네임으로 이관합니다.
- 사용하지 않는 `profile_image_url`을 제거합니다.
- 모든 사용자에게 닉네임을 요구하고 중복을 막도록 `NOT NULL`과 `UNIQUE` 제약을 추가합니다.
