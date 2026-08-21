-- Refresh Token 갱신 뒤에도 최초 소셜 로그인 제공자를 유지하기 위한 컬럼을 추가한다.
ALTER TABLE auth_sessions ADD COLUMN provider VARCHAR(20);

-- 이 마이그레이션 이전에는 Google 로그인만 있었으므로 기존 세션을 GOOGLE로 이관한다.
UPDATE auth_sessions SET provider = 'GOOGLE' WHERE provider IS NULL;

-- 기존 데이터를 채운 뒤 신규 세션에는 제공자가 반드시 저장되도록 제한한다.
ALTER TABLE auth_sessions MODIFY COLUMN provider VARCHAR(20) NOT NULL;
