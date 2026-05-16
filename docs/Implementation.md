## 구현계획

- 현재 PPG 데이터를 카메라를 이용해서 추출해내는 기능은 현재 클론한 프로젝트에 있음.
- 여기에 voice data를 함께 추출하여, 어플리케이션 사용자의 디렉토리에 저장하고자함.
- 또한 촬영을 통해 얻은 PPG 데이터도 디렉토리에 저장하게 하는 것이 목적
- 이는 추후 AI 모델 학습에 voice + ppg 센서 데이터를 이용하기 위함임.

---

## 현재 코드베이스 분석 (클론 원본 기능)

### 주요 소스 파일

| 파일 | 역할 |
|------|------|
| `MainActivity.java` | 앱 진입점, 카메라/PPG 처리, UI, 로깅 |
| `WebClient.java` | WebSocket 클라이언트 (실시간 PPG 스트리밍) |
| `javaViewCameraControl.java` | OpenCV 기반 카메라 뷰 + 플래시 제어 (현재 미사용) |
| `DoubleTwoDimQueue.java` | 2D 큐 자료구조 |
| `fftLib.java` | FFT 연산 라이브러리 |

### 현재 구현된 기능

1. **PPG 신호 추출**
   - 후면 카메라 + LED 플래시로 손가락 끝 촬영
   - YUV_420_888 포맷 프레임에서 Y 채널(밝기) 평균값 추출
   - 지수이동평균(EMA, alpha=0.2) 필터링
   - 256 샘플 슬라이딩 버퍼 유지

2. **심박수(BPM) 계산**
   - 3-포인트 로컬 최대값(피크) 검출
   - 피크 간격 중앙값으로 BPM 산출 (`60000 / medianInterval`)
   - 45~180 BPM 범위 외 값 필터링
   - 10초 이내 피크만 유효로 처리

3. **데이터 저장**
   - **PPG(펄스) 로그**: `pulse_YYYY-MM-DD_HH-mm-ss.txt`
     - 형식: `경과시간(ms); BPM\n`
     - 저장 위치: `getFilesDir()` (앱 내부 저장소, `/data/data/com.example.projekcik/files/`)
   - **호흡 로그**: `breath_YYYY-MM-DD_HH-mm-ss.txt`
     - 형식: `경과시간(ms); inhale|exhale\n`
     - 저장 위치: 동일 (앱 내부 저장소)
   - **동영상**: `Movies/ppg_better/video_YYYYMMDD_HHmmss.mp4` (MediaStore, 외부 저장소)

4. **실시간 WebSocket 스트리밍**
   - 입력한 IP로 `ws://<ip>:8765` 연결
   - 각 프레임마다 `timestamp average` 형식으로 RAW PPG 값 전송

5. **UI**
   - 실시간 LineChart (MPAndroidChart)
   - 측정 타이머
   - BPM 표시
   - IP 입력 필드
   - 호흡 버튼 (현재 `visibility=gone`으로 숨겨진 상태)

### 현재 저장되지 않는 것
- **음성(마이크) 데이터**: 완전히 미구현
- **RAW PPG 타임시리즈**: BPM만 저장, 프레임별 average 값 미저장 (WebSocket으로만 전송됨)
- **외부 접근 가능 경로 저장**: 펄스/호흡 로그가 앱 내부에만 저장돼 PC 전송이 불편함

---

## AI 학습용 데이터 포맷

PPG는 동영상 자체가 아니라, **프레임별 밝기 평균값의 1D 시계열(CSV)** 을 저장한다.
모델 입력은 `(timestamp_ms, ppg_raw)` 수치 시퀀스이며, Python에서 bandpass 필터링 + HRV 추출을 별도로 수행한다.

### 수집 후 파일 구조 (클립 단위)

```
Downloads/ppg_data/
└── P01/                          ← 피실험자 ID
    └── A_happy_01/               ← clip_id (파트_감정/조건_순번)
        ├── ppg.csv               ← timestamp_ms, ppg_raw (프레임별)
        ├── voice.wav             ← 16kHz mono PCM
        └── meta.json             ← clip_id, participant_id, part, emotion_label, veracity_label, duration_sec
    └── B_fake_Q2/
        ├── ppg.csv
        ├── voice.wav
        └── meta.json
```

### meta.json 예시

```json
{
  "clip_id": "A_happy_01",
  "participant_id": "P01",
  "part": "A",
  "emotion_label": "happy",
  "veracity_label": null,
  
  "duration_sec": 16
}
```

Part A (감정 발화): `emotion_label` = happy/sad/angry/... , `veracity_label` = null
Part B (거짓/참 발화): `emotion_label` = null , `veracity_label` = true/fake

---

## TODO (구현 목록)

### Step 1: 권한 추가
- `AndroidManifest.xml`에 `RECORD_AUDIO` 권한 추가

### Step 2: VoiceRecorder 클래스 생성
- `AudioRecord` API 사용 (16kHz, mono, PCM 16-bit)
- 측정 시작/종료와 동기화하여 WAV 파일 저장

### Step 3: RAW PPG CSV 저장
- 기존 `appendLogToFile`에서 BPM 저장하던 것을 **프레임별 `(timestamp_ms, ppg_raw)` CSV** 로 교체
- BPM/filtered 값은 Python 후처리로 넘기고 앱에서는 raw만 저장

### Step 4: UI 설계 (A+C 방식)

**화면 1 - 참가자 선택 (SessionActivity)**
- 참가자 ID 직접 입력 or 드롭다운으로 기존 참가자 선택
  - 드롭다운: `Downloads/ppg_data/` 하위 폴더 목록을 읽어서 기존 참가자 ID 표시
- [세션 시작] 버튼 → 화면 2로 이동

**화면 2 - 체크리스트 (ChecklistActivity)**
- 상단에 참가자 ID 표시
- Part A 체크리스트: 8감정 버튼 그리드 (완료 시 ✅ 표시)
- Part B 체크리스트: true × 5클립, fake × 5클립 버튼 (완료 시 ✅ 표시)
- 미완료 항목 탭 → 화면 3으로 이동
- 완료 여부는 `Downloads/ppg_data/{participant_id}/` 폴더 존재 여부로 판단

**화면 3 - 측정 화면 (MainActivity - 기존)**
- 상단에 현재 클립 정보 표시 (예: `P01 | Part A | happy`)
- 기존 PPG 측정 + 신규 음성 녹음 동시 진행
- [측정 시작] → PPG + Voice 동시 녹화 시작
- [측정 종료] → 파일 저장 + meta.json 생성 → 체크리스트 화면으로 복귀

### Step 5: 저장 경로 통일
- 모든 파일을 `Downloads/ppg_data/{participant_id}/{clip_id}/` 에 저장
- clip_id 형식: `A_happy_01`, `B_true_Q2`, `B_fake_Q5`
- 클립 종료 시 Toast로 저장 완료 알림 + 체크리스트 자동 업데이트