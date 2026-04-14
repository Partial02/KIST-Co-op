//package io.github.rladmstj.esmserver.model;
//
//import org.springframework.boot.autoconfigure.domain.EntityScan;
//import jakarta.persistence.*;
//import java.time.LocalDateTime;
//
//import jakarta.persistence.Entity;
//import jakarta.persistence.Table;
//import jakarta.persistence.Id;
//import jakarta.persistence.Column;
//@Entity
//
//@Table(name="SensorInfo")
//
//public class SensorInfo {
//
//    @Id
//    @Column(name = "sensor_id", length = 50)
//    private String sensorId;
//
//    @Column(name = "type", length = 30, nullable = false)
//    private String type;
//
//    @Column(name = "category", length = 50, nullable = false)
//    private String category;
//
//    @Column(name = "location", length = 100)
//    private String location;
//
//    @Column(name = "description")
//    private String description;
//
//    @Column(name = "registered_at")
//    private LocalDateTime registeredAt;
//}
