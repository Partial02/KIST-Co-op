package io.github.rladmstj.esmserver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rladmstj.esmserver.dto.openai.OpenAiChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VoiceIntentService {

    private final WebClient openAiWebClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
                당신은 사용자의 짧은 음성 명령을 보고,\s
                앱을 시작하거나 설문 다음 질문으로 넘어가라는 명령인지 아닌지를 판단하는 어시스턴트입니다. \s
                설명 없이 다음 JSON 형식만 반환하세요:
                   
                {
                  "intent": "START_APP | DO_NOTHING"
                }
                   
                판단 규칙:
                1) 사용자의 발화가 아래 중 하나라도 포함하거나, 문맥상 '앱을 시작해 달라' 또는 '다음 질문/다음 단계로 진행하라'는 의미로 판단되면 → intent=START_APP:
                   - "응", "시작", "시작해", "응답할래", "켜줘", "응 해줘", "응 알겠어", "그래", "설문 시작해", "응 알림 받았어"
                   - "다음", "넘어가", "진행해", "계속", "오케이", "네", "괜찮아", "좋아", "됐어"
                   - 발음 오류, 띄어쓰기 오류, 오타 등도 유연하게 매칭
                   
                2) 그 외 대답 (무관하거나 아무 의미 없음) → intent=DO_NOTHING
                   
                반드시 JSON만 반환하세요.
                   
            *유의 사항: 사용자의 발화에는 발음 오류, 띄어쓰기 오류 등 오타가 있을 수 있습니다. 문맥상 일치한다고 판단되면 유연하게 매칭해 주세요.
          
            """;

    public String interpret(String utterance) throws Exception {
        Map<String, Object> body = Map.of(
                "model", "gpt-4o-mini",
                "temperature", 0.0,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", "발화: \"" + utterance + "\"")
                )
        );

        OpenAiChatResponse aiResp = openAiWebClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(OpenAiChatResponse.class)
                .block();

        String content = aiResp.choices().get(0).message().content();
        int startIdx = content.indexOf("{");
        int endIdx = content.lastIndexOf("}") + 1;

        if (startIdx == -1 || endIdx == -1) {
            throw new IllegalStateException("응답에 JSON이 없습니다: " + content);
        }

        String json = content.substring(startIdx, endIdx);
        JsonNode node = mapper.readTree(json);

        return node.get("intent").asText();
    }
}
