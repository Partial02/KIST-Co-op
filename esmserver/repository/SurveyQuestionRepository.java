package io.github.rladmstj.esmserver.repository;

import io.github.rladmstj.esmserver.model.SurveyQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SurveyQuestionRepository extends JpaRepository<SurveyQuestion, String> {
}
