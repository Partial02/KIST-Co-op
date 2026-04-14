package io.github.rladmstj.esmserver.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;


@Entity
@Table(name = "SurveyResponse")
public class SurveyResponse {

    @Id
    @Column(name = "response_id", nullable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long responseId;

    @ManyToOne
    @JoinColumn(name = "session_id", nullable = false)
    @NotNull(message = "Session 정보는 필수입니다.")
    private SurveySession session;

    @Column(name = "question_id", nullable = false)
    @NotNull(message = "QuestionId는 필수입니다.")
    private String questionId;

    @Column(name = "answer", nullable = false)
    @NotNull(message = "Answer는 필수입니다.")
    private String answer;
    @Enumerated(EnumType.STRING)
    @Column(name = "answer_type", nullable = false)
    @NotNull(message = "AnswerType은 필수입니다.")
    private AnswerType answerType;
    // ✅ Getter & Setter
    public Long getResponseId() {
        return responseId;
    }

    public SurveySession getSession() {
        return session;
    }

    public void setSession(SurveySession session) {
        this.session = session;
    }

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

    public AnswerType getAnswerType() {
        return answerType;
    }

    public void setAnswerType(AnswerType answerType) {
        this.answerType = answerType;
    }
}
