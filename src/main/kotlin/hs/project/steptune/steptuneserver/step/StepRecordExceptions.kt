package hs.project.steptune.steptuneserver.step

/** 한 동기화 요청 안에 같은 날짜가 두 번 들어왔을 때 어떤 값이 최종값인지 모호하므로 발생한다. */
class DuplicateStepRecordDateException(recordDate: String) :
    RuntimeException("Duplicate step record date: $recordDate")

/** 음수 걸음 수가 Bean Validation을 우회해 Service까지 들어왔을 때도 DB 작업 전에 발생한다. */
class InvalidStepCountException :
    RuntimeException("stepCount must be greater than or equal to 0")

/** 날짜 쿼리 파라미터가 실제 `YYYY-MM-DD` 날짜가 아닐 때 발생한다. */
class InvalidStepRecordDateException(parameterName: String) :
    RuntimeException("$parameterName must be a valid YYYY-MM-DD date")

/** 기간의 시작일/종료일 순서가 잘못됐거나 허용한 최대 조회 기간을 넘었을 때 발생한다. */
class InvalidStepRecordRangeException :
    RuntimeException("Step record range must be ordered and no longer than 366 days")
