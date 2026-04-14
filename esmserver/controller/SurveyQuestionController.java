package io.github.rladmstj.esmserver.controller;

import io.github.rladmstj.esmserver.model.SurveyQuestion;
import io.github.rladmstj.esmserver.repository.SurveyQuestionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/survey-questions")
public class SurveyQuestionController {

    @Autowired
    private SurveyQuestionRepository surveyQuestionRepository;

    // 전체 질문 목록 조회
    @GetMapping
    public List<SurveyQuestion> getAllQuestions() {
        return surveyQuestionRepository.findAll();
    }

    // 특정 질문 조회
    @GetMapping("/{id}")
    public SurveyQuestion getQuestion(@PathVariable String id) {
        return surveyQuestionRepository.findById(id).orElse(null);
    }

    // 질문 추가
    @PostMapping
    public SurveyQuestion createQuestion(@RequestBody SurveyQuestion question) {
        return surveyQuestionRepository.save(question);
    }
//  추가하는 데이터 수신 예시
//{
//    "questionId": "q3",
//        "text": "가장 많이 먹는 간식은 무엇인가요?",
//        "options": ["과자", "과일", "아이스크림", "기타"]
//}


    // 질문 삭제
    @DeleteMapping("/{id}")
    public void deleteQuestion(@PathVariable String id) {
        surveyQuestionRepository.deleteById(id);
    }
}
