import cv2
import numpy as np
from pyk4a import Config, PyK4A, ColorResolution, DepthMode
from ultralytics import YOLO

# Azure Kinect 설정
config = Config(
    color_resolution=ColorResolution.RES_1080P,
    depth_mode=DepthMode.OFF,
    synchronized_images_only=False
)
k4a = PyK4A(config)
k4a.start()

# YOLOv8 Pose 모델 로드
model = YOLO("yolov8m-pose.pt")

# ROI 사각형 4점 정의
roi_pts = np.array([
    [649, 568],
    [1107, 650],
    [983, 1080],
    [304, 885]
], dtype=np.int32)

# ID별 ROI 진입 상태 저장용 딕셔너리
roi_status = {}

# COCO 포맷 기준 관절 연결
SKELETON_CONNECTIONS = [
    (5, 6), (5, 7), (7, 9), (6, 8), (8, 10),
    (11, 12), (11, 13), (13, 15), (12, 14), (14, 16),
    (5, 11), (6, 12),
    (0, 1), (1, 2), (2, 3), (3, 4),
    (0, 5), (0, 6)
]

while True:
    capture = k4a.get_capture()
    if capture.color is None:
        continue

    frame_bgr = cv2.cvtColor(capture.color, cv2.COLOR_BGRA2BGR)

    # YOLO 추론 + 트래킹
    results = model.track(
        source=frame_bgr,
        persist=True,
        tracker="bytetrack.yaml",
        classes=[0],
        verbose=False
    )

    result = results[0]
    current_ids = set()

    if result.keypoints is not None:
        for box, conf, track_id, keypoint_set in zip(result.boxes.xyxy, result.boxes.conf, result.boxes.id, result.keypoints.xy):
            if conf < 0.7:
                continue

            tid = int(track_id)
            current_ids.add(tid)

            # 관절 15, 16 모두 ROI 안에 있어야 진입
            foot_in_roi = []
            for kp_idx in [15, 16]:
                x, y = keypoint_set[kp_idx]
                if x > 0 and y > 0:
                    in_roi = cv2.pointPolygonTest(roi_pts, (int(x), int(y)), False) >= 0
                    foot_in_roi.append(in_roi)
                else:
                    foot_in_roi.append(False)

            in_roi = all(foot_in_roi)

            # 상태 변화 감지
            if tid not in roi_status:
                roi_status[tid] = False

            if in_roi and not roi_status[tid]:
                print(f"✅ ID {tid} → ROI 진입")
                roi_status[tid] = True
            elif not in_roi and roi_status[tid]:
                print(f"❎ ID {tid} → ROI 이탈")
                roi_status[tid] = False

            # 바운딩 박스 시각화
            x1, y1, x2, y2 = map(int, box.tolist())
            cv2.rectangle(frame_bgr, (x1, y1), (x2, y2), (0, 255, 0), 2)
            cv2.putText(frame_bgr, f"ID:{tid} Conf:{conf:.2f}", (x1, y1 - 10),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 1)

            # 관절 시각화 + 번호
            for i, (x, y) in enumerate(keypoint_set):
                if x > 0 and y > 0:
                    x_int, y_int = int(x), int(y)
                    cv2.circle(frame_bgr, (x_int, y_int), 3, (255, 0, 255), -1)
                    cv2.putText(frame_bgr, str(i), (x_int + 3, y_int - 3),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.4, (255, 0, 255), 1)

            # 관절 연결선 그리기
            for a, b in SKELETON_CONNECTIONS:
                x1, y1 = keypoint_set[a]
                x2, y2 = keypoint_set[b]
                if x1 > 0 and y1 > 0 and x2 > 0 and y2 > 0:
                    pt1 = (int(x1), int(y1))
                    pt2 = (int(x2), int(y2))
                    cv2.line(frame_bgr, pt1, pt2, (0, 255, 255), 2)

    # ROI 외 ID 상태 제거
    roi_status = {tid: roi_status[tid] for tid in roi_status if tid in current_ids}

    # ROI 시각화
    cv2.polylines(frame_bgr, [roi_pts], isClosed=True, color=(255, 0, 0), thickness=2)

    # 결과 출력
    cv2.imshow("ROI + YOLOv8 Pose Tracking", frame_bgr)
    if cv2.waitKey(1) & 0xFF == ord("q"):
        break

k4a.stop()
cv2.destroyAllWindows()
