//package io.github.rladmstj.esmserver.controller;
//
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//
//@RestController
//@RequestMapping("/api/settings")
//public class SurveySettingsController {
//
//    private static final int MIN_INTERVAL_MINUTES = 1; // 원하는 값 설정
//
//    @GetMapping("/min-interval")
//    public int getMinIntervalMinutes() {
//        return MIN_INTERVAL_MINUTES;
//    }
//}

package io.github.rladmstj.esmserver.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SurveySettingsController {

    private static final int MIN_INTERVAL_MINUTES = 60; // 랜덤 알림 간 최소 간격 (분)
    private static final int REMINDER_MINUTES = 5;      // 리마인더 알림 시간 (분)
    private static final int WINDOW_MINUTES = 20;       // 설문 응답 가능 시간창 (분)

    @GetMapping("/all")
    public Map<String, Integer> getAllSettings() {
        return Map.of(
                "minIntervalMinutes", MIN_INTERVAL_MINUTES,
                "reminderMinutes", REMINDER_MINUTES,
                "windowMinutes", WINDOW_MINUTES
        );
    }
}
