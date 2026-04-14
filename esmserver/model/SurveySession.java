package io.github.rladmstj.esmserver.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "SurveySession")

public class SurveySession {
    @Id
    @Column(name = "session_id", nullable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long sessionId;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private Users users;

    @Column(name = "answered_at", nullable = false)
    private LocalDateTime answeredAt;

    @Column(name = "schedule_slot")
    private Integer scheduleSlot;



    // ✅ Getter & Setter 추가
    public Long getSessionId() {
        return sessionId;
    }

    public Users getUser() {
        return users;
    }

    @JsonProperty("user")
    public void setUser(Users users) {
        this.users = users;
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
}
