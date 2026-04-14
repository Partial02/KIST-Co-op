package io.github.rladmstj.esmserver.dto;

import java.time.LocalDateTime;
import java.util.List;

public class SurveySubmitRequestDTO {
    private String userId;

    private LocalDateTime answeredAt;
    private Integer scheduleSlot;
    private List<SurveyAnswerDTO> responses;

    // Getters & Setters 생략하지 마세요!
    //
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public LocalDateTime getAnsweredAt() {
        return answeredAt;
    }

    public void setAnsweredAt(LocalDateTime answeredAt) {
        this.answeredAt = answeredAt;
    }

    public Integer getScheduleSlot() {
        return scheduleSlot;
    }

    public void setScheduleSlot(Integer scheduleSlot) {
        this.scheduleSlot = scheduleSlot;
    }

    public List<SurveyAnswerDTO> getResponses() {
        return responses;
    }

    public void setResponses(List<SurveyAnswerDTO> responses) {
        this.responses = responses;
    }
}
