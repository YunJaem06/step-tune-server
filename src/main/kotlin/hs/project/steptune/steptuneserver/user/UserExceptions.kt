package hs.project.steptune.steptuneserver.user

/** 닉네임이 비어 있거나 DB가 허용하는 길이를 넘었을 때 발생한다. */
class InvalidNicknameException : RuntimeException("Nickname must be between 1 and 30 characters")

/** 다른 사용자가 이미 같은 닉네임을 사용 중일 때 발생한다. */
class NicknameAlreadyExistsException : RuntimeException("Nickname is already in use")
