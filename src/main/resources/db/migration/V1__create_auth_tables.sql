-- 앱 내부 사용자 정보를 저장한다. UUID는 MySQL에 전용 타입이 없어 CHAR(36)으로 저장한다.
CREATE TABLE app_users (
    id CHAR(36) NOT NULL PRIMARY KEY,
    display_name VARCHAR(100),
    profile_image_url VARCHAR(2048),
    created_at TIMESTAMP(6) NOT NULL,
    last_login_at TIMESTAMP(6) NOT NULL
);

-- 검증된 소셜 제공자 계정과 Step Tune 내부 사용자를 연결한다.
CREATE TABLE social_accounts (
    id CHAR(36) NOT NULL PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    email VARCHAR(320),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_social_accounts_user
        FOREIGN KEY (user_id) REFERENCES app_users (id) ON DELETE CASCADE,
    CONSTRAINT uk_social_accounts_provider_subject
        UNIQUE (provider, provider_subject)
);

CREATE INDEX idx_social_accounts_user_id ON social_accounts (user_id);

-- 자동 로그인과 로그아웃을 서버에서 통제하기 위한 Refresh Token 세션을 저장한다.
CREATE TABLE auth_sessions (
    id CHAR(36) NOT NULL PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    refresh_token_hash VARCHAR(64) NOT NULL,
    device_name VARCHAR(100),
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    last_used_at TIMESTAMP(6),
    revoked_at TIMESTAMP(6),
    CONSTRAINT fk_auth_sessions_user
        FOREIGN KEY (user_id) REFERENCES app_users (id) ON DELETE CASCADE,
    CONSTRAINT uk_auth_sessions_refresh_token_hash UNIQUE (refresh_token_hash)
);

CREATE INDEX idx_auth_sessions_user_id ON auth_sessions (user_id);
