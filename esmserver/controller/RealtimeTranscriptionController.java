package io.github.rladmstj.esmserver.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
public class RealtimeTranscriptionController {

    private final WebClient openAiWebClient;

    public RealtimeTranscriptionController(WebClient openAiWebClient) {
        this.openAiWebClient = openAiWebClient;
    }

    @PostMapping("/api/realtime/transcribe-token")
    public Mono<ResponseEntity<String>> createTranscriptionSession() {
        return openAiWebClient.post()
                .uri("/realtime/sessions")
                .bodyValue(Map.of(
                        "input_audio_format", "pcm16",
                        "input_audio_transcription", Map.of(
                                "model", "gpt-4o-transcribe",
                                "prompt", "일상 대화",
                                "language", "ko"
                        ),
                        "turn_detection", Map.of(
                                "type", "server_vad",
                                "threshold", 0.5,
                                "prefix_padding_ms", 300,
                                "silence_duration_ms", 500
                        ),
                        "input_audio_noise_reduction", Map.of("type", "near_field"),
                        "include", List.of("item.input_audio_transcription.logprobs")
                ))
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    e.printStackTrace(); // 디버깅용 로그 추가
                    return Mono.just(ResponseEntity.internalServerError().body("❌ 실패: " + e.getMessage()));
                });
    }

}