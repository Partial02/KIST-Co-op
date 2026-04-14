package io.github.rladmstj.esmserver.controller;

import io.github.rladmstj.esmserver.model.SurveyResponse;
import io.github.rladmstj.esmserver.repository.SurveyResponseRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/survey-responses")
public class SurveyResponseController {

    @Autowired
    private SurveyResponseRepository surveyResponseRepository;

    // 모든 응답 조회
    @GetMapping
    public List<SurveyResponse> getAllResponses() {
        return surveyResponseRepository.findAll();
    }

    // 단일 응답 조회
    @GetMapping("/{id}")
    public  ResponseEntity<SurveyResponse> getResponse(@PathVariable Long id) {
        return surveyResponseRepository.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    // 응답 저장 (단일)
    @PostMapping
    public SurveyResponse saveResponse( @Valid @RequestBody SurveyResponse response) {
        return surveyResponseRepository.save(response);
    }
    @PostMapping("/bulk")
    public List<SurveyResponse> saveResponses(@RequestBody List<SurveyResponse> responses) {
        return surveyResponseRepository.saveAll(responses);
    }


    // 응답 삭제
    @DeleteMapping("/{id}")
    public void deleteResponse(@PathVariable Long id) {
        surveyResponseRepository.deleteById(id);
    }
}
