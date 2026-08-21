-- 로그인 요청과 세션에서 더 이상 사용하지 않는 기기 이름 컬럼을 제거한다.
ALTER TABLE auth_sessions DROP COLUMN device_name;
