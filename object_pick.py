import cv2
import numpy as np
from ultralytics import YOLO
import mediapipe as mp
from datetime import datetime
import time

# YOLO 설정
model = YOLO("yolov8m.pt")
target_classes = [64, 65, 76, 67, 77]
class_name_map = {
    64: "마우스",
    65: "리모컨",
    76: "가위",
    67: "핸드폰",
    77: "곰인형"
}
class_conf_thresholds = {
    64: 0.10,
    65: 0.40,
    76: 0.25,
    67: 0.50,
    77: 0.20
}

# ROI 내부 판별
def is_inside_roi(x, y, roi):
    return cv2.pointPolygonTest(roi, (x, y), False) >= 0

# 상태 정의
def get_current_state(remote_in_roi, hand_in_roi):
    if not remote_in_roi and not hand_in_roi:
        return 1
    elif hand_in_roi:
        return 2
    elif remote_in_roi and not hand_in_roi:
        return 3

def run_object_pick(last_drawer_opened):
        
        # Mediapipe 초기화
        mp_hands = mp.solutions.hands
        hands = mp_hands.Hands(static_image_mode=False, max_num_hands=2)
        mp_drawing = mp.solutions.drawing_utils

        # 상태 및 버퍼 초기화
        prev_state = None
        object_detected_history = []
        last_confirmed_class_id = None
        last_seen_class_id = None
        pending_put = False
        state_enter_time = time.time()

        history_length = 10
        min_true_count = 6
    
        roi_dict = {
            0: np.array([[867, 520], [1139, 521], [1144, 791], [856, 785]], dtype=np.int32),
            2: np.array([[503, 255], [1346, 235], [1380, 1054], [459, 1039]], dtype=np.int32),
            5: np.array([[1299, 522], [1918, 499], [1918, 834], [1308, 837]], dtype=np.int32),
            6: np.array([[773, 476], [1187, 471], [1198, 850], [754, 844]], dtype=np.int32)
        }
        roi_idx = (last_drawer_opened or 100) % 100
        roi_pts = roi_dict.get(roi_idx)
        if roi_pts is None:
            print(f"⚠️ ROI index {roi_idx}에 해당하는 ROI가 정의되지 않았습니다.")
            return

        # 웹캠 설정
        cap = cv2.VideoCapture(0)
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1920)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 1080)

        while True:
            ret, frame = cap.read()
            if not ret:
                break

            hand_in_roi = False
            remote_in_roi_frame = False
            detected_class_id = None

            # YOLO 감지
            results = model(frame, verbose=False)[0]
            for det in results.boxes.data:
                x1, y1, x2, y2, score, cls_id = det
                cls_id = int(cls_id)
                conf_thresh = class_conf_thresholds.get(cls_id, 0.5)
                if cls_id in target_classes and score >= conf_thresh:
                    cx = int((x1 + x2) / 2)
                    cy = int((y1 + y2) / 2)
                    if is_inside_roi(cx, cy, roi_pts):
                        remote_in_roi_frame = True
                        detected_class_id = cls_id
                        last_seen_class_id = cls_id  # ✅ 항상 기억
                    cv2.rectangle(frame, (int(x1), int(y1)), (int(x2), int(y2)), (0, 255, 0), 2)
                    cv2.putText(frame, class_name_map.get(cls_id, str(cls_id)), (int(x1), int(y1) - 10),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)

            # 감지 버퍼
            object_detected_history.append(remote_in_roi_frame)
            if len(object_detected_history) > history_length:
                object_detected_history.pop(0)
            remote_in_roi = object_detected_history.count(True) >= min_true_count

            # 손 감지
            rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            results_hands = hands.process(rgb_frame)
            if results_hands.multi_hand_landmarks:
                for hand_landmarks in results_hands.multi_hand_landmarks:
                    for lm in hand_landmarks.landmark:
                        x = int(lm.x * frame.shape[1])
                        y = int(lm.y * frame.shape[0])
                        if is_inside_roi(x, y, roi_pts):
                            hand_in_roi = True
                            break
                    mp_drawing.draw_landmarks(frame, hand_landmarks, mp_hands.HAND_CONNECTIONS)

            # 상태 판단
            current_state = get_current_state(remote_in_roi, hand_in_roi)
            timestamp = datetime.now().strftime("%H:%M:%S")

            # 2→1 전이 딜레이 처리
            if prev_state == 2 and current_state == 1:
                if time.time() - state_enter_time < 0.5:
                    current_state = 2

            # 상태 전이 시 처리
            if prev_state is None:
                print(f"[현재 상태] 상황 {current_state}")
                state_enter_time = time.time()

            elif current_state != prev_state:
                print(f"[현재 상태] 상황 {current_state}")
                transition = (prev_state, current_state)

                if transition == (1, 2):
                    #print(f"👀 손이 들어왔어요 (물건 넣을 준비) ({timestamp})")
                    pending_put = True

                elif transition in [(2, 3), (1, 3)]:
                    use_cls = detected_class_id or last_seen_class_id
                    if pending_put and use_cls is not None:
                        name = class_name_map.get(use_cls, f"클래스 {use_cls}")
                        print(f"✅ {name}이 ROI에 놓였어요! ({timestamp})")
                        last_confirmed_class_id = use_cls
                        pending_put = False
                        cap.release()
                        cv2.destroyAllWindows()
                        return last_seen_class_id+400

                #elif transition == (3, 2):
                    #print(f"👀 손이 들어왔어요 (물건 꺼낼 준비) ({timestamp})")

                elif transition in [(2, 1), (3, 1)]:
                    use_cls = last_confirmed_class_id or last_seen_class_id
                    if use_cls is not None:
                        name = class_name_map.get(use_cls, f"클래스 {use_cls}")
                        print(f"✅ {name}이 꺼내졌어요! ({timestamp})")
                        last_confirmed_class_id = None
                        pending_put = False
                        cap.release()
                        cv2.destroyAllWindows()
                        return use_cls + 500

                state_enter_time = time.time()

            prev_state = current_state

            # ROI 시각화
            cv2.polylines(frame, [roi_pts], isClosed=True, color=(0, 0, 255), thickness=2)
            cv2.imshow("Drawer Monitor", frame)

            if cv2.waitKey(1) & 0xFF == ord("q"):
                break

        cap.release()
        cv2.destroyAllWindows()