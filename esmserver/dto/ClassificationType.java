package io.github.rladmstj.esmserver.dto;

/** LLM이 내려주는 분류 결과 */
public enum ClassificationType {
    OPTION,          // 특정 옵션과 일치
    ETC,             // '기타'
    FREE_INPUT_RELATED, // 자유입력 질문 & 연관 있음
    ACTION,
    QUESTION,
    UNRELATED        // 전혀 관련 없음

}