package io.github.rladmstj.esmserver.controller;

import io.github.rladmstj.esmserver.service.VoiceIntentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/intent")
@RequiredArgsConstructor
public class VoiceIntentController {

    private final VoiceIntentService voiceIntentService;

    @PostMapping
    public Map<String, String> checkIntent(@RequestBody Map<String, String> body) throws Exception {
        String utterance = body.get("utterance");
        String intent = voiceIntentService.interpret(utterance);
        return Map.of("intent", intent); // 결과를 JSON으로 응답
    }
}
