// dto/openai/OpenAiChatResponse.java
package io.github.rladmstj.esmserver.dto.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiChatResponse(List<Choice> choices) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String content) {}
}
