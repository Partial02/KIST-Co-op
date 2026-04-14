package io.github.rladmstj.esmserver.controller;

import io.github.rladmstj.esmserver.dto.AlarmBatchRequest;
import java.io.FileWriter;
import java.io.IOException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    @PostMapping("/batch")
    public ResponseEntity<String> receiveAlarmBatch(@RequestBody AlarmBatchRequest req) {
        
        // bash에 로그 출력
        System.out.println("📥 전체 알람 수신 (유저: " + req.userId + ", 모드: " + req.mode + ")");
        System.out.println("총 알람 개수: " + req.windows.size());

        // 로그 파일도 저장
        try (FileWriter writer = new FileWriter("alarm_log.txt", true)) {  // true = append
            for (AlarmBatchRequest.AlarmWindow w : req.windows) {
                String log = String.format(
                    "%s,%s,%d,%d%n",
                    req.userId,
                    req.mode,
                    w.start,
                    w.end
                );
                writer.write(log);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    
        return ResponseEntity.ok("received");
    }
}
