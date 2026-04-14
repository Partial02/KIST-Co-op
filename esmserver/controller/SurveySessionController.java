package io.github.rladmstj.esmserver.controller;

import io.github.rladmstj.esmserver.model.Users;
import io.github.rladmstj.esmserver.model.SurveySession;
import io.github.rladmstj.esmserver.repository.UsersRepository;
import io.github.rladmstj.esmserver.repository.SurveySessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/survey-sessions")
public class SurveySessionController {

    @Autowired
    private UsersRepository usersRepository;
    
    @Autowired
    private SurveySessionRepository surveySessionRepository;

    // 모든 세션 조회
    @GetMapping
    public List<SurveySession> getAllSessions() {
        return surveySessionRepository.findAll();
    }

    // 특정 세션 조회
    @GetMapping("/{id}")
    public SurveySession getSession(@PathVariable Long id) {
        return surveySessionRepository.findById(id).orElse(null);
    }

    // 세션 저장
    @PostMapping
    public SurveySession saveSession(@RequestBody SurveySession session) {
        // 유저 ID만 가져오기
        String userId = session.getUser().getUserId();
    
        // ① UsersRepository로 실제 영속화된 Users 객체 가져오기
        Users user = usersRepository.findById(userId)
                        .orElseThrow(() -> new RuntimeException("User not found"));
    
        // ② 영속화된 Users를 다시 set
        session.setUser(user);
    
        return surveySessionRepository.save(session); // ✔ OK
    }

    // 세션 삭제
    @DeleteMapping("/{id}")
    public void deleteSession(@PathVariable Long id) {
        surveySessionRepository.deleteById(id);
    }
}
