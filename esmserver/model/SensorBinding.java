package io.github.rladmstj.esmserver.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "SensorBinding")
public class SensorBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sensor_id")
    private Long sensorId;  // 기존 SensorInfo의 PK

    @Column(name = "sensor_type", length = 50, nullable = false)
    private String sensorType;

    // 사용자 엔티티와 연관
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private Users users;

    @Column(name = "is_connected", nullable = false)
    private Boolean isConnected;

    @Column(name = "location", length = 100)
    private String location;

    // ✅ Getter & Setter
    public Long getSensorId() {
        return sensorId;
    }

    public void setSensorId(Long sensorId) {
        this.sensorId = sensorId;
    }


    public String getSensorType() {
        return sensorType;
    }

    public void setSensorType(String sensorType) {
        this.sensorType = sensorType;
    }

    public Users getUsers() {
        return users;
    }

    public void setUsers(Users users) {
        this.users = users;
    }

    public Boolean getIsConnected() {
        return isConnected;
    }

    public void setIsConnected(Boolean isConnected) {
        this.isConnected = isConnected;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }
}

//
//@Entity
//
//@Table(name="SensorBinding")
//public class SensorBinding {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;
//
//    @ManyToOne
//    @JoinColumn(name = "sensor_id", nullable = false)
//    private SensorInfo sensor;
//
//    @ManyToOne
//    @JoinColumn(name = "user_id", nullable = false)
//    private Users users;
//
//    @Column(name = "start_time", nullable = false)
//    private LocalDateTime startTime;
//
//    @Column(name = "end_time")
//    private LocalDateTime endTime;
//}
//
