package io.github.rladmstj.esmserver.dto;

/** 프런트 → 서버 : 질문 ID + STT 결과 */
public record SpeechInterpretationRequest(
        String questionId,
        String utterance
) {}