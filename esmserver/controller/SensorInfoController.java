//package io.github.rladmstj.esmserver.controller;
//
////public class SensorInfoController {
////}
//
//
//import io.github.rladmstj.esmserver.model.SensorInfo;
//import io.github.rladmstj.esmserver.repository.SensorInfoRepository;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.List;
//
//@RestController
//@RequestMapping("/api/sensors")
//public class SensorInfoController {
//
//    @Autowired
//    private SensorInfoRepository sensorInfoRepository;
//
//    // 모든 센서 조회
//    @GetMapping
//    public List<SensorInfo> getAllSensors() {
//        return sensorInfoRepository.findAll();
//    }
//
//    // 센서 하나 조회
//    @GetMapping("/{id}")
//    public SensorInfo getSensor(@PathVariable String id) {
//        return sensorInfoRepository.findById(id).orElse(null);
//    }
//
//    // 센서 저장
//    @PostMapping
//    public SensorInfo saveSensor(@RequestBody SensorInfo sensorInfo) {
//        return sensorInfoRepository.save(sensorInfo);
//    }
//
//    // 센서 삭제
//    @DeleteMapping("/{id}")
//    public void deleteSensor(@PathVariable String id) {
//        sensorInfoRepository.deleteById(id);
//    }
//}
