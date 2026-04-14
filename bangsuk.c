#include <BluetoothSerial.h>

BluetoothSerial SerialBT;  // ESP32용 블루투스 시리얼

const int fsrPins[4] = {34, 35, 32, 33};  // ESP32 ADC 핀 (A0~A3 대신 사용)
int fsrValues[4];

void setup() {
  Serial.begin(115200);        // PC용 디버깅
  SerialBT.begin("ESP32-FSR"); // 블루투스 기기 이름
  Serial.println("✅ Bluetooth Ready");
}

void loop() {
  String data = "";

  for (int i = 0; i < 4; i++) {
    fsrValues[i] = analogRead(fsrPins[i]);
    data += "," + String(fsrValues[i]);
  }

  // 블루투스로 전송
  SerialBT.println(data);

  // 디버깅용 출력
  Serial.println("📤 블루투스 전송 데이터");
  Serial.println(data);
  Serial.println();

  delay(1000);
}
