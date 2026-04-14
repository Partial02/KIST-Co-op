package io.github.rladmstj.esmserver.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rladmstj.esmserver.model.SensorBinding;
import io.github.rladmstj.esmserver.model.SensorCAM;
import io.github.rladmstj.esmserver.repository.SensorBindingRepository;
import io.github.rladmstj.esmserver.repository.SensorCAMRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/sensor-cam")
public class SensorCAMController {

    private final SensorCAMRepository sensorCAMRepository;
    private final SensorBindingRepository sensorBindingRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public SensorCAMController(
            SensorCAMRepository sensorCAMRepository,
            SensorBindingRepository sensorBindingRepository,
            ObjectMapper objectMapper
    ) {
        this.sensorCAMRepository = sensorCAMRepository;
        this.sensorBindingRepository = sensorBindingRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<?> saveSensorCAM(HttpServletRequest request) throws IOException {
        JsonNode root = objectMapper.readTree(request.getInputStream());
        System.out.println("📥 받은 JSON (SensorCAM): " + root.toPrettyString());

        List<SensorCAM> savedList = new ArrayList<>();

        try {
            if (root.isArray()) {
                for (JsonNode node : root) {
                    SensorCAM data = objectMapper.treeToValue(node, SensorCAM.class);
                    ResponseEntity<?> res = handleSave(data);
                    if (!res.getStatusCode().is2xxSuccessful()) return res;
                    savedList.add(data);
                }
                return ResponseEntity.ok(savedList);
            } else if (root.isObject()) {
                SensorCAM data = objectMapper.treeToValue(root, SensorCAM.class);
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

    private ResponseEntity<?> handleSave(SensorCAM sensorCAM) {
        if (sensorCAM == null) {
            return ResponseEntity.badRequest().body("sensorCAM이 null입니다.");
        }

        if (sensorCAM.getSensor() == null || sensorCAM.getSensor().getSensorId() == null) {
            return ResponseEntity.badRequest().body("sensorId가 필요합니다.");
        }

        Long sensorId = sensorCAM.getSensor().getSensorId();
        SensorBinding binding = sensorBindingRepository.findById(sensorId).orElse(null);
        if (binding == null) {
            return ResponseEntity.badRequest().body("존재하지 않는 센서입니다. sensorId=" + sensorId);
        }

        sensorCAM.setSensor(binding);
        sensorCAMRepository.save(sensorCAM);
        System.out.println("✅ SensorCAM 저장 완료: sensorId=" + sensorId);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public List<SensorCAM> getAllCAMData() {
        return sensorCAMRepository.findAll();
    }

    @GetMapping("/sensor/{sensorId}")
    public List<SensorCAM> getCAMDataBySensor(@PathVariable Long sensorId) {
        return sensorCAMRepository.findBySensor_SensorId(sensorId);
    }
}
