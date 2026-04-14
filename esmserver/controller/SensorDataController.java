package io.github.rladmstj.esmserver.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rladmstj.esmserver.model.SensorBinding;
import io.github.rladmstj.esmserver.model.SensorData;
import io.github.rladmstj.esmserver.repository.SensorBindingRepository;
import io.github.rladmstj.esmserver.repository.SensorDataRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/sensor-data")
public class SensorDataController {

    private final SensorDataRepository sensorDataRepository;
    private final SensorBindingRepository sensorBindingRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public SensorDataController(
            SensorDataRepository sensorDataRepository,
            SensorBindingRepository sensorBindingRepository,
            ObjectMapper objectMapper
    ) {
        this.sensorDataRepository = sensorDataRepository;
        this.sensorBindingRepository = sensorBindingRepository;
        this.objectMapper = objectMapper;
    }

    // ✅ 단일 또는 배열 모두 수용하는 POST
    @PostMapping
    public ResponseEntity<?> saveSensorData(HttpServletRequest request) throws IOException {
        JsonNode root = objectMapper.readTree(request.getInputStream());
        System.out.println("📥 받은 JSON: " + root.toPrettyString());

        List<SensorData> savedList = new ArrayList<>();

        try {
            if (root.isArray()) {
                for (JsonNode node : root) {
                    SensorData data = objectMapper.treeToValue(node, SensorData.class);
                    ResponseEntity<?> res = handleSave(data);
                    if (!res.getStatusCode().is2xxSuccessful()) return res;
                    savedList.add(data);
                }
                return ResponseEntity.ok(savedList);
            } else if (root.isObject()) {
                SensorData data = objectMapper.treeToValue(root, SensorData.class);
                ResponseEntity<?> res = handleSave(data);
                if (!res.getStatusCode().is2xxSuccessful()) return res;
                return ResponseEntity.ok(data);
            } else {
                return ResponseEntity.badRequest().body("❌ JSON 형식이 객체나 배열이 아닙니다.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("❌ 파싱 또는 저장 실패: " + e.getMessage());
        }
    }

    // ✅ 공통 저장 로직
    private ResponseEntity<?> handleSave(SensorData sensorData) {
        if (sensorData == null) {
            return ResponseEntity.badRequest().body("sensorData가 null입니다.");
        }

        if (sensorData.getSensor() == null || sensorData.getSensor().getSensorId() == null) {
            return ResponseEntity.badRequest().body("sensorId가 필요합니다.");
        }

        Long sensorId = sensorData.getSensor().getSensorId();
        SensorBinding binding = sensorBindingRepository.findById(sensorId).orElse(null);
        if (binding == null) {
            return ResponseEntity.badRequest().body("존재하지 않는 센서입니다. sensorId=" + sensorId);
        }

        sensorData.setSensor(binding);
        sensorDataRepository.save(sensorData);
        System.out.println("✅ 저장 완료: sensorId=" + sensorId + ", value=" + sensorData.getValue());
        return ResponseEntity.ok().build();
    }

    // ✅ 전체 조회
    @GetMapping
    public List<SensorData> getAllData() {
        return sensorDataRepository.findAll();
    }

    // ✅ 특정 센서 ID 조회
    @GetMapping("/sensor/{sensorId}")
    public List<SensorData> getDataBySensor(@PathVariable Long sensorId) {
        return sensorDataRepository.findBySensor_SensorId(sensorId);
    }
}

