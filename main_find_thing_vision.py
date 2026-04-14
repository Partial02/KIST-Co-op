import asyncio
import websockets
import subprocess
from websockets.exceptions import ConnectionClosedOK, ConnectionClosedError
from people_track import run_tracking
from object_pick import run_object_pick
import time

# 전역 변수
last_value = None

# ROS2 퍼블리시
def publish(value: int):
    subprocess.run([
        "bash", "-c",
        f"source /opt/ros/humble/setup.bash && python3 publisher_helper.py {value}"
    ])

# WebSocket 핸들러
async def handler(ws):
    global last_value

    print("WebSocket 클라이언트 연결됨")
    result = run_tracking()
    if result == 1:
        print("✔ 3초 이상 ROI 유지 감지 완료")
        await ws.send("300")

    try:
        async for msg in ws:
            try:
                msg_clean = msg.strip()
                if msg_clean == "1":
                    if last_value is not None:
                        print(f"'1' 수신됨 → 이전 명령값 {last_value} 기반 publish({last_value % 100})")
                        publish(last_value % 100)
                else:
                    value = int(msg_clean)
                    if 100 <= value < 110:
                        print(f"수신된 명령값: {value}")
                        last_value = value
                        time.sleep(2)
                        publish(value)
                        signal = run_object_pick(last_value)
                        print(signal)
                        if signal:
                            asyncio.create_task(ws.send(str(signal)))

                        
            except ValueError:
                pass
            except Exception as e:
                print(f"오류 발생: {e}")
    except (ConnectionClosedOK, ConnectionClosedError):
        print("연결 종료")

# 서버 실행
async def main():
    async with websockets.serve(handler, "0.0.0.0", port=9090):
        print("WebSocket 서버 실행 중")
        await asyncio.Future()

if __name__ == "__main__":
    asyncio.run(main())
