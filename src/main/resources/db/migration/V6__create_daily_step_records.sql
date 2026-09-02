-- 사용자별 하루 총걸음 수를 보관하는 테이블이다.
CREATE TABLE daily_step_records (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    record_date DATE NOT NULL,
    step_count INT NOT NULL,
    measured_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_daily_step_records_user
        FOREIGN KEY (user_id) REFERENCES app_users (id) ON DELETE CASCADE,
    CONSTRAINT uk_daily_step_records_user_date UNIQUE (user_id, record_date),
    CONSTRAINT chk_daily_step_records_step_count CHECK (step_count >= 0)
);

