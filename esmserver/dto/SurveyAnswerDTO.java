package io.github.rladmstj.esmserver.dto;

import io.github.rladmstj.esmserver.model.AnswerType;

//public class SurveyAnswerDTO {
//    private Long questionId;
//    private String answer;
//
//    // Getters & Setters
//    // ✅ Getter
//    public Long getQuestionId() {
//        return questionId;
//    }
//
//    public String getAnswer() {
//        return answer;
//    }
//
//    // ✅ Setter
//    public void setQuestionId(Long questionId) {
//        this.questionId = questionId;
//    }
//
//    public void setAnswer(String answer) {
//        this.answer = answer;
//    }
//
//}
public class SurveyAnswerDTO {
    private String questionId; // ✅ String으로 바꿔야 함
    private String answer;
    private AnswerType answerType;

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public AnswerType getAnswerType() { return answerType; }
    public void setAnswerType(AnswerType answerType) { this.answerType = answerType; }
}
