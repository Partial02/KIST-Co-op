package io.github.rladmstj.esmserver.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "SensorData")
public class SensorData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "data_id")
    private Long dataId;

    @ManyToOne
    @JoinColumn(name = "sensor_id", nullable = false)
    private SensorBinding sensor; // SensorInfo가 아니라 SensorBinding 기준으로 연결됨

    @Column(name = "sensor_time", nullable = false)
    private LocalDateTime sensorTime;

    @Column(name = "value")
    private Integer value;

    // ✅ Getter/Setter
    public Long getDataId() {
        return dataId;
    }

    public void setDataId(Long dataId) {
        this.dataId = dataId;
    }

    public SensorBinding getSensor() {
        return sensor;
    }

    public void setSensor(SensorBinding sensor) {
        this.sensor = sensor;
    }

    public LocalDateTime getSensorTime() {
        return sensorTime;
    }

    public void setSensorTime(LocalDateTime sensorTime) {
        this.sensorTime = sensorTime;
    }

    public Integer getValue() {
        return value;
    }

    public void setValue(Integer value) {
        this.value = value;
    }
}
