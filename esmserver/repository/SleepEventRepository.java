package io.github.rladmstj.esmserver.repository;

import io.github.rladmstj.esmserver.model.SleepEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public interface SleepEventRepository extends JpaRepository<SleepEvent, Long> {

    // 중복 저장 방지용 (실제 사용)
    boolean existsBySensorIdAndSensorTime(Integer sensorId, Timestamp sensorTime);

    // 최근 시각 조회(자동 폴러가 윈도우 계산할 때 사용)
    SleepEvent findTopBySensorIdOrderBySensorTimeDesc(Integer sensorId);

    // 조회용 보조 메소드(기간)
    List<SleepEvent> findBySensorIdAndSensorTimeBetween(Integer sensorId, Timestamp start, Timestamp end);
}
