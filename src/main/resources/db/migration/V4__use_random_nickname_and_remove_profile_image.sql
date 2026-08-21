-- 소셜 제공자 표시 이름 대신 앱에서 직접 관리하는 닉네임으로 컬럼 의미를 변경한다.
ALTER TABLE app_users RENAME COLUMN display_name TO nickname;

-- 기존 사용자가 있다면 UUID 앞부분을 이용해 충돌 가능성이 낮은 초기 닉네임을 부여한다.
UPDATE app_users
SET nickname = CONCAT('스텝러너', SUBSTRING(REPLACE(CAST(id AS CHAR), '-', ''), 1, 12));

-- 앞으로 모든 사용자는 닉네임을 가져야 하며 현재 엔티티의 최대 길이는 30자다.
ALTER TABLE app_users MODIFY COLUMN nickname VARCHAR(30) NOT NULL;

-- 현재 앱에서 사용하지 않는 소셜 프로필 이미지 컬럼을 제거한다.
ALTER TABLE app_users DROP COLUMN profile_image_url;

-- 프로필 수정 시에도 같은 닉네임을 두 사용자가 선택하지 못하게 한다.
ALTER TABLE app_users ADD CONSTRAINT uk_app_users_nickname UNIQUE (nickname);
