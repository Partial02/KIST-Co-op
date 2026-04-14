package io.github.rladmstj.esmserver.controller;

import io.github.rladmstj.esmserver.model.SensorBinding;
import io.github.rladmstj.esmserver.repository.SensorBindingRepository;
import io.github.rladmstj.esmserver.repository.UsersRepository;
import io.github.rladmstj.esmserver.model.Users;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sensor-bindings")
public class SensorBindingController {

    @Autowired
    private SensorBindingRepository sensorBindingRepository;

    @Autowired
    private UsersRepository usersRepository;

    // 모든 바인딩 조회
    @GetMapping
    public List<SensorBinding> getAllBindings() {
        return sensorBindingRepository.findAll();
    }

    // 단일 바인딩 조회
    @GetMapping("/{sensorId}")
    public ResponseEntity<SensorBinding> getBinding(@PathVariable Long sensorId) {
        return sensorBindingRepository.findById(sensorId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // 바인딩 저장
    @PostMapping
    public ResponseEntity<?> saveBinding(@RequestBody SensorBinding binding) {
        // 사용자가 실제 존재하는지 확인
        if (binding.getUsers() == null || !usersRepository.existsById(binding.getUsers().getUserId())) {
            return ResponseEntity.badRequest().body("존재하지 않는 사용자입니다.");
        }

        return ResponseEntity.ok(sensorBindingRepository.save(binding));
    }

    // 바인딩 수정
    @PutMapping("/{sensorId}")
    public ResponseEntity<?> updateBinding(@PathVariable Long sensorId, @RequestBody SensorBinding updated) {
        return sensorBindingRepository.findById(sensorId).map(binding -> {
            binding.setSensorType(updated.getSensorType());
            binding.setUsers(updated.getUsers());
            binding.setIsConnected(updated.getIsConnected());
            binding.setLocation(updated.getLocation());
            return ResponseEntity.ok(sensorBindingRepository.save(binding));
        }).orElse(ResponseEntity.notFound().build());
    }

    // 바인딩 삭제
    @DeleteMapping("/{sensorId}")
    public ResponseEntity<Void> deleteBinding(@PathVariable Long sensorId) {
        if (!sensorBindingRepository.existsById(sensorId)) {
            return ResponseEntity.notFound().build();
        }
        sensorBindingRepository.deleteById(sensorId);
        return ResponseEntity.noContent().build();
    }
}
