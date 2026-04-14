package io.github.rladmstj.esmserver.repository;

import io.github.rladmstj.esmserver.model.SurveySession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SurveySessionRepository extends JpaRepository<SurveySession, Long> {
}
