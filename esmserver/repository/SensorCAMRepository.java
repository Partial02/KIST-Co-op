package io.github.rladmstj.esmserver.repository;

import io.github.rladmstj.esmserver.model.SensorCAM;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SensorCAMRepository extends JpaRepository<SensorCAM, Long> {
    List<SensorCAM> findBySensor_SensorId(Long sensorId);
}
