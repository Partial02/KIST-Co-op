package io.github.rladmstj.esmserver.dto;

import java.util.List;

/** 서버 → 프런트 : 분류 결과 */
public record InterpretationResponse(
        ClassificationType type,
        List<String> matchedOption,   // OPTION일 때만 값, 나머진 null/"" 가능
 
        String related         // FREE_INPUT_RELATED만 true, 그 외 null
 
) {}
