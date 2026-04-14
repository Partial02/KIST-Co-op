package io.github.rladmstj.esmserver.controller;

//public class SurveySubmitController {
//}
import io.github.rladmstj.esmserver.dto.SurveyAnswerDTO;
import io.github.rladmstj.esmserver.dto.SurveySubmitRequestDTO;
import io.github.rladmstj.esmserver.model.SurveySession;
import io.github.rladmstj.esmserver.repository.SurveySessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.github.rladmstj.esmserver.model.SurveyResponse;
import io.github.rladmstj.esmserver.repository.SurveyResponseRepository;
import io.github.rladmstj.esmserver.model.SurveyQuestion;
import io.github.rladmstj.esmserver.repository.SurveyQuestionRepository;
import io.github.rladmstj.esmserver.model.Users;
import io.github.rladmstj.esmserver.repository.UsersRepository;

@RestController
@RequestMapping("/api/survey")
public class SurveySubmitController {

    @Autowired private SurveySessionRepository sessionRepo;
    @Autowired private SurveyResponseRepository responseRepo;
    @Autowired private UsersRepository userRepo;
    @Autowired private SurveyQuestionRepository questionRepo;

    @PostMapping("/submit")
//    public ResponseEntity<String> submitSurvey(@RequestBody SurveySubmitRequestDTO dto) {
//
//        // 1. 유저 가져오기
//        Users users = userRepo.findById(dto.getUserId())
//                .orElseThrow(() -> new RuntimeException("Users not found"));
//
//        // 2. 세션 생성
//        SurveySession session = new SurveySession();
//        session.setUser(users);
//        session.setAnsweredAt(dto.getAnsweredAt());
//        session.setScheduleSlot(dto.getScheduleSlot());
//
//        session = sessionRepo.save(session); // 저장 후 sessionId 확보
//
//        // 3. 응답 저장
//        for (SurveyAnswerDTO answerDTO : dto.getResponses()) {
//            SurveyQuestion question = questionRepo.findById(answerDTO.getQuestionId())
//                    .orElseThrow(() -> new RuntimeException("Question not found"));
//
//            SurveyResponse response = new SurveyResponse();
//            response.setSession(session);
//            response.setQuestion(question);
//            response.setAnswer(answerDTO.getAnswer());
//
//            responseRepo.save(response);
//        }
//
//        return ResponseEntity.ok("Survey submitted successfully");
//    }
    public ResponseEntity<String> submitSurvey(@RequestBody SurveySubmitRequestDTO dto) {

        // 1. 유저 가져오기
        Users users = userRepo.findById(dto.getUserId())
                .orElseThrow(() -> new RuntimeException("Users not found"));

        // 2. 세션 생성
        SurveySession session = new SurveySession();
        session.setUser(users);
        session.setAnsweredAt(dto.getAnsweredAt());
        session.setScheduleSlot(dto.getScheduleSlot());

        session = sessionRepo.save(session); // 저장 후 sessionId 확보

        // 3. 응답 저장
        for (SurveyAnswerDTO answerDTO : dto.getResponses()) {
            SurveyResponse response = new SurveyResponse();
            response.setSession(session);
            response.setQuestionId(answerDTO.getQuestionId()); // ✅ 여기 핵심
            response.setAnswer(answerDTO.getAnswer());
            response.setAnswerType(answerDTO.getAnswerType()); // ✅ 추가된 부분
            responseRepo.save(response);
        }

        return ResponseEntity.ok("Survey submitted successfully");
    }
}

//앱에서 이런식으로 보내면 session, response다 만들게
// {
//        "userId": "user123",
//        "answeredAt": "2025-06-30T09:00:00",
//        "scheduleSlot": 2,
//        "responses": [
//        { "questionId": 1, "answer": "매우 그렇다" },
//        { "questionId": 2, "answer": "보통이다" }
//        ]ㅇㅇ