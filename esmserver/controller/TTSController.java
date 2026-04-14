//package io.github.rladmstj.esmserver.controller;
//import org.springframework.web.bind.annotation.*;
//
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//
//import com.google.auth.oauth2.GoogleCredentials;
//import com.google.cloud.texttospeech.v1.AudioConfig;
//import com.google.cloud.texttospeech.v1.AudioEncoding;
//import com.google.cloud.texttospeech.v1.SsmlVoiceGender;
//import com.google.cloud.texttospeech.v1.SynthesisInput;
//import com.google.cloud.texttospeech.v1.SynthesizeSpeechResponse;
//import com.google.cloud.texttospeech.v1.TextToSpeechClient;
//import com.google.cloud.texttospeech.v1.TextToSpeechSettings;
//import com.google.cloud.texttospeech.v1.VoiceSelectionParams;
//import com.google.api.gax.core.FixedCredentialsProvider;
//
//import java.io.FileInputStream;
//import java.util.Map;
//
//@CrossOrigin(origins = "*")
//
//
//@RestController
//@RequestMapping("/api/tts")
//public class TTSController {
//
//    @PostMapping(produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
//    public ResponseEntity<byte[]> tts(@RequestBody Map<String, String> body) throws Exception {
//        System.out.println("🔊 TTS 요청 도착: " + body.get("text"));
//
//        String text = body.get("text");
//
//        // 서비스 계정 키 로드
//        GoogleCredentials credentials = GoogleCredentials.fromStream(
//                new FileInputStream("/Users/kim-eunseo/Desktop/KoreaUniversity/kist/esmkey1/google-tts-key.json")
////        ""/home/ubuntu/googletts.json")
//        );
//        TextToSpeechSettings settings = TextToSpeechSettings.newBuilder()
//                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
//                .build();
//
//        // TTS 클라이언트 생성
//        try (TextToSpeechClient textToSpeechClient = TextToSpeechClient.create(settings)) {
//            SynthesisInput input = SynthesisInput.newBuilder().setText(text).build();
//            VoiceSelectionParams voice = VoiceSelectionParams.newBuilder()
//                    .setLanguageCode("ko-KR")
//                    .setSsmlGender(SsmlVoiceGender.FEMALE)
//                    .build();
//            AudioConfig audioConfig = AudioConfig.newBuilder()
//                    .setAudioEncoding(AudioEncoding.MP3)
//                    .build();
//
//            SynthesizeSpeechResponse response = textToSpeechClient.synthesizeSpeech(input, voice, audioConfig);
//
//            byte[] audioContent = response.getAudioContent().toByteArray();
//
//            return ResponseEntity.ok()
//                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=voice.mp3")
//                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
//                    .body(audioContent);
//        }
//    }
//}
package io.github.rladmstj.esmserver.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.FileInputStream;
import java.util.*;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/tts")
public class TTSController {

    private static final String GOOGLE_TTS_ENDPOINT = "https://texttospeech.googleapis.com/v1/text:synthesize";
    private static final String SERVICE_ACCOUNT_KEY_PATH = "/home/kist/googletts.json";
//    private static final String SERVICE_ACCOUNT_KEY_PATH = "/Users/kim-eunseo/Desktop/KoreaUniversity/kist/esmkey1/google-tts-key.json";
    private static final String PROJECT_ID = "consummate-sled-464602-a0"; // 🔴 수정 필요: 실제 GCP 프로젝트 ID 입력

    @PostMapping(produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> tts(@RequestBody Map<String, String> body) throws Exception {
//        String text = body.get("text");
        String text = " ... " + body.get("text");  // 또는 "... " + body.get("text");
        System.out.println("🔊 TTS 요청 도착: " + text);

        // ✅ 1. 서비스 계정 키로 credentials 생성 및 access token 추출
        GoogleCredentials credentials = GoogleCredentials
                .fromStream(new FileInputStream(SERVICE_ACCOUNT_KEY_PATH))
                .createScoped("https://www.googleapis.com/auth/cloud-platform");
        credentials.refreshIfExpired();
        String accessToken = credentials.getAccessToken().getTokenValue();

        // ✅ 2. REST 요청 바디 구성
        Map<String, Object> input = Map.of("text", text);


        Map<String, Object> voice = Map.of(
                "languageCode", "ko-KR",
                "name", "ko-KR-Chirp3-HD-Aoede"
        );
        Map<String, Object> audioConfig = Map.of(
                "audioEncoding", "MP3"
//                "speakingRate", 0.95 // ✅ 조금 느리게 설정
        );

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("input", input);
        requestBody.put("voice", voice);
        requestBody.put("audioConfig", audioConfig);

        // ✅ 3. HTTP 헤더 + 요청 생성
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("X-Goog-User-Project", PROJECT_ID); // 🔴 필수: GCP 프로젝트 ID
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> entity = new HttpEntity<>(new ObjectMapper().writeValueAsString(requestBody), headers);

        // ✅ 4. REST API 호출
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<Map> response = restTemplate.exchange(
                GOOGLE_TTS_ENDPOINT,
                HttpMethod.POST,
                entity,
                Map.class
        );

        // ✅ 5. 응답 처리
        String base64Audio = (String) response.getBody().get("audioContent");
        byte[] audioBytes = Base64.getDecoder().decode(base64Audio);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=chirp3.mp3")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(audioBytes);
    }
}
