package io.github.rladmstj.esmserver.model;

import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import com.vladmihalcea.hibernate.type.json.JsonType;
import org.hibernate.annotations.Type;
import jakarta.persistence.*;

import java.util.List;
//@Entity
//@Table(name = "SurveyQuestion")
//public class SurveyQuestion {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
////    private Long questionId;
//    private String questionId;
//    @Column(name = "text",nullable = false)
//    private String text;
//}
@Entity
@Table(name = "SurveyQuestion")
public class SurveyQuestion {

    @Id
    @Column(name = "question_id")
    private String questionId;  // 👈 auto-increment 아님 (GeneratedValue 제거)

    @Column(name = "text", nullable = false)
    private String text;

    @Type(JsonBinaryType.class)  // ✅ 최신 Hibernate 방식
    @Column(name = "options", columnDefinition = "jsonb")
    private List<String> options;
    // ✅ getter/setter 추가 필요
    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
    public List<String> getOptions() {
        return options;
    }

    public void setOptions(List<String> options) {
        this.options = options;
    }
}
