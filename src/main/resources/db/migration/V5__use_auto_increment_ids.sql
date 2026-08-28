-- 기존 UUID 사용자마다 가입 순서 기준의 숫자 ID를 한 번만 배정하는 임시 매핑 테이블이다.
CREATE TABLE user_id_v5_mapping (
    new_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    old_id CHAR(36) NOT NULL,
    CONSTRAINT uk_user_id_v5_mapping_old_id UNIQUE (old_id)
);

-- 기존 사용자의 생성 순서를 최대한 유지하고, 같은 시각이면 UUID 순서로 결과를 고정한다.
INSERT INTO user_id_v5_mapping (old_id)
SELECT id
FROM app_users
ORDER BY created_at, id;

-- 앞으로 MySQL이 1, 2, 3... 순서로 userId를 발급할 새 사용자 테이블이다.
CREATE TABLE app_users_v5 (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    nickname VARCHAR(30) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    last_login_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_app_users_v5_nickname UNIQUE (nickname)
);

-- UUID 사용자 데이터를 새 숫자 ID와 함께 복사한다.
INSERT INTO app_users_v5 (id, nickname, created_at, last_login_at)
SELECT mapping.new_id, users.nickname, users.created_at, users.last_login_at
FROM app_users users
JOIN user_id_v5_mapping mapping ON mapping.old_id = users.id;

-- 소셜 계정 자체 ID와 사용자 외래 키도 BIGINT 자동 증가 방식으로 통일한다.
CREATE TABLE social_accounts_v5 (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    email VARCHAR(320),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_social_accounts_v5_user
        FOREIGN KEY (user_id) REFERENCES app_users_v5 (id) ON DELETE CASCADE,
    CONSTRAINT uk_social_accounts_v5_provider_subject
        UNIQUE (provider, provider_subject)
);

CREATE INDEX idx_social_accounts_v5_user_id ON social_accounts_v5 (user_id);

-- 기존 소셜 계정은 provider/subject/email을 유지하고 사용자 FK만 새 숫자로 치환한다.
INSERT INTO social_accounts_v5 (user_id, provider, provider_subject, email, created_at)
SELECT mapping.new_id, accounts.provider, accounts.provider_subject, accounts.email, accounts.created_at
FROM social_accounts accounts
JOIN user_id_v5_mapping mapping ON mapping.old_id = accounts.user_id
ORDER BY accounts.created_at, accounts.id;

-- Refresh Token 해시와 만료·폐기 상태를 그대로 유지할 새 숫자형 세션 테이블이다.
CREATE TABLE auth_sessions_v5 (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    refresh_token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    last_used_at TIMESTAMP(6),
    revoked_at TIMESTAMP(6),
    CONSTRAINT fk_auth_sessions_v5_user
        FOREIGN KEY (user_id) REFERENCES app_users_v5 (id) ON DELETE CASCADE,
    CONSTRAINT uk_auth_sessions_v5_refresh_token_hash UNIQUE (refresh_token_hash)
);

CREATE INDEX idx_auth_sessions_v5_user_id ON auth_sessions_v5 (user_id);

-- 기존 Refresh 세션은 사용자 FK만 새 숫자로 바꾸고 토큰 상태는 모두 보존한다.
INSERT INTO auth_sessions_v5 (
    user_id,
    provider,
    refresh_token_hash,
    created_at,
    expires_at,
    last_used_at,
    revoked_at
)
SELECT
    mapping.new_id,
    sessions.provider,
    sessions.refresh_token_hash,
    sessions.created_at,
    sessions.expires_at,
    sessions.last_used_at,
    sessions.revoked_at
FROM auth_sessions sessions
JOIN user_id_v5_mapping mapping ON mapping.old_id = sessions.user_id
ORDER BY sessions.created_at, sessions.id;

-- 외래 키 자식부터 기존 UUID 테이블을 제거한다.
DROP TABLE auth_sessions;
DROP TABLE social_accounts;
DROP TABLE app_users;

-- 새 숫자형 테이블을 애플리케이션이 사용하는 기존 이름으로 교체한다.
ALTER TABLE app_users_v5 RENAME TO app_users;
ALTER TABLE social_accounts_v5 RENAME TO social_accounts;
ALTER TABLE auth_sessions_v5 RENAME TO auth_sessions;

-- 데이터 전환에만 사용한 UUID 매핑은 더 이상 필요하지 않으므로 제거한다.
DROP TABLE user_id_v5_mapping;
