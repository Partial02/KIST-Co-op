package io.github.rladmstj.esmserver.controller;

import io.github.rladmstj.esmserver.dto.InterpretationResponse;
import io.github.rladmstj.esmserver.dto.SpeechInterpretationRequest;
import io.github.rladmstj.esmserver.service.InterpretationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
@RestController
@RequestMapping("/api/survey")
@RequiredArgsConstructor
public class llmController {

    private final InterpretationService service;

    @PostMapping("/interpret")
    public InterpretationResponse interpret(@RequestBody SpeechInterpretationRequest req) throws Exception {
        return service.interpret(req);
    }


}