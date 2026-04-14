package io.github.rladmstj.esmserver.repository;

import io.github.rladmstj.esmserver.model.SensorData;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SensorDataRepository extends JpaRepository<SensorData, Long> {
    List<SensorData> findBySensor_SensorId(Long sensorId);
}
