// MainActivity.java
// PPG 측정 화면: 카메라로 PPG 신호 추출 + 마이크로 음성 동시 녹음
// ChecklistActivity에서 참가자/클립 정보를 받아 측정 후 Downloads/ppg_data/{participant_id}/{clip_id}/ 에 저장

package com.example.projekcik;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 200;

    // PPG 신호 처리 상수
    private static final double EMA_ALPHA = 0.2;
    private static final int FFT_SIZE = 256;

    private SurfaceHolder surfaceHolder;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;
    private String cameraId;
    private CameraManager cameraManager;
    private boolean isRecording = false;
    private boolean isBreathing = false;
    private long startTime = 0;
    private long webSocketSendCounter = 0;
    private TextView timerTextView;

    private LineChart lineChart;
    private LineDataSet lineDataSet;
    private LineData lineData;
    private ArrayList<Entry> entries = new ArrayList<>();
    private float elapsedTime = 0f;

    private final BlockingQueue<Double> greenSamples = new ArrayBlockingQueue<>(256);
    private TextView heartRateTextView;
    private Double filteredValue = null;

    ImageReader imageReader;

    // 호흡 버튼: 기존 코드 호환성 유지 (gone 상태)
    public Button breathButton;

    // --- 클립 정보 (ChecklistActivity에서 전달받음) ---
    private String participantId;
    private String clipId;
    private String part;
    private String emotionLabel;
    private String veracityLabel;

    // --- 클립 정보 표시 TextView ---
    private TextView tvClipInfo;

    // --- PPG CSV 저장 ---
    // ppgWriter는 측정 시작 시 열고 종료 시 닫음
    private FileWriter ppgCsvWriter;

    // --- 음성 녹음 ---
    private VoiceRecorder voiceRecorder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Intent에서 클립 정보 수신
        Intent intent = getIntent();
        participantId  = intent.getStringExtra(SessionActivity.EXTRA_PARTICIPANT_ID);
        clipId         = intent.getStringExtra(ChecklistActivity.EXTRA_CLIP_ID);
        part           = intent.getStringExtra(ChecklistActivity.EXTRA_PART);
        emotionLabel   = intent.getStringExtra(ChecklistActivity.EXTRA_EMOTION_LABEL);
        veracityLabel  = intent.getStringExtra(ChecklistActivity.EXTRA_VERACITY_LABEL);

        // 클립 정보 TextView 설정
        tvClipInfo = findViewById(R.id.tvClipInfo);
        updateClipInfoDisplay();

        timerTextView = findViewById(R.id.timerTextView);
        breathButton = findViewById(R.id.buttonBreath);

        SurfaceView surfaceView = findViewById(R.id.surfaceView);
        surfaceHolder = surfaceView.getHolder();

        heartRateTextView = findViewById(R.id.heartRateTextView);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        // 차트 초기화
        lineChart = findViewById(R.id.lineChart);
        lineDataSet = new LineDataSet(entries, "Heart Rate Signal");
        lineDataSet.setColor(Color.RED);
        lineDataSet.setDrawCircles(false);
        lineDataSet.setLineWidth(2f);
        lineData = new LineData(lineDataSet);
        lineChart.setData(lineData);
        lineChart.getDescription().setEnabled(false);
        lineChart.getAxisRight().setEnabled(false);
        lineChart.getXAxis().setDrawLabels(false);
        lineChart.getLegend().setEnabled(false);

        // VoiceRecorder 초기화
        voiceRecorder = new VoiceRecorder();

        if (checkPermissions()) {
            setupCamera();
        }
    }

    /**
     * 상단 클립 정보 TextView 업데이트
     * 예: "P01 | Part A | happy" 또는 "P01 | Part B | true Q3"
     */
    private void updateClipInfoDisplay() {
        if (tvClipInfo == null) return;

        if (participantId == null || clipId == null) {
            tvClipInfo.setText(getString(R.string.clip_info_none));
            return;
        }

        String label;
        if ("A".equals(part)) {
            label = emotionLabel != null ? emotionLabel : "";
        } else {
            label = veracityLabel != null ? veracityLabel : "";
            // B_true_Q3 → "true Q3"
            if (clipId.contains("_Q")) {
                String qPart = clipId.substring(clipId.lastIndexOf("_") + 1); // "Q3"
                label = label + " " + qPart;
            }
        }

        String displayText = participantId + " | Part " + part + " | " + label;
        tvClipInfo.setText(displayText);
    }

    // ======================== 타이머 ========================

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            long elapsedMillis = System.currentTimeMillis() - startTime;
            int seconds = (int) (elapsedMillis / 1000);
            int minutes = seconds / 60;
            seconds = seconds % 60;
            timerTextView.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
            timerHandler.postDelayed(this, 1000);
        }
    };

    private void startTimerRunnable() {
        timerHandler.post(timerRunnable);
    }

    private void stopTimerRunnable() {
        timerHandler.removeCallbacks(timerRunnable);
    }

    // ======================== 권한 ========================

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupCamera();
            } else {
                Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private boolean checkPermissions() {
        boolean cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean audioOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        List<String> needed = new ArrayList<>();
        if (!cameraOk) needed.add(Manifest.permission.CAMERA);
        if (!audioOk)  needed.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            boolean writeOk = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
            if (!writeOk) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    needed.toArray(new String[0]), REQUEST_CAMERA_PERMISSION);
            return false;
        }
        return true;
    }

    // ======================== 카메라 설정 ========================

    private void setupCamera() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    openCamera();
                    break;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera setup error: " + e.toString());
        }
    }

    // ======================== 측정 시작/종료 버튼 ========================

    /**
     * 측정 토글: 측정 시작 시 PPG 캡처 + 음성 녹음 동시 시작
     *            측정 종료 시 모든 파일 저장 후 ChecklistActivity로 복귀
     */
    public void onToggleRecording(View view) {
        Button button = (Button) view;
        if (!isRecording) {
            try {
                if (captureSession != null) {
                    captureSession.close();
                    captureSession = null;
                }
                prepareMediaRecorder();
                if (cameraId == null) {
                    Toast.makeText(this, "카메라를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show();
                    return;
                }
                startTime = System.currentTimeMillis();
                webSocketSendCounter = System.currentTimeMillis();
                startTimerRunnable();
                openCamera();
                button.setText(R.string.measurement_button_end);
                isRecording = true;

                // PPG CSV 파일 열기
                openPpgCsvWriter();

                // 음성 녹음 시작
                startVoiceRecording();

            } catch (Exception e) {
                Toast.makeText(this, "녹음 시작 오류", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Start recording error: " + e.toString());
            }

        } else {
            // 측정 종료
            stopRecording();
            stopTimerRunnable();
            button.setText(R.string.measurement_button_start);
            isRecording = false;
            startTime = 0;
        }
    }

    // ======================== PPG CSV 저장 ========================

    /**
     * 클립 저장 디렉토리 반환: Downloads/ppg_data/{participantId}/{clipId}/
     */
    private File getClipDir() {
        File ppgRoot = SessionActivity.getPpgRootDir();
        File participantDir = new File(ppgRoot, participantId != null ? participantId : "unknown");
        return new File(participantDir, clipId != null ? clipId : "unknown_clip");
    }

    /**
     * PPG CSV 파일 열기 (측정 시작 시 호출)
     */
    private void openPpgCsvWriter() {
        File clipDir = getClipDir();
        if (!clipDir.exists() && !clipDir.mkdirs()) {
            Log.e(TAG, "클립 디렉토리 생성 실패: " + clipDir.getAbsolutePath());
            return;
        }

        File ppgCsvFile = new File(clipDir, "ppg.csv");
        try {
            // 새 파일로 덮어씀 (재촬영 지원)
            ppgCsvWriter = new FileWriter(ppgCsvFile, false);
            ppgCsvWriter.write("timestamp_ms,ppg_raw\n");
            ppgCsvWriter.flush();
            Log.i(TAG, "PPG CSV 열기: " + ppgCsvFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "PPG CSV 파일 열기 오류", e);
            ppgCsvWriter = null;
        }
    }

    /**
     * 프레임별 PPG 값 CSV에 추가 (analyzeImage에서 호출)
     */
    private void appendPpgFrame(long timestampMs, double ppgRaw) {
        if (ppgCsvWriter == null) return;
        try {
            ppgCsvWriter.write(timestampMs + "," + ppgRaw + "\n");
        } catch (IOException e) {
            Log.e(TAG, "PPG 프레임 기록 오류", e);
        }
    }

    /**
     * PPG CSV 파일 닫기
     */
    private void closePpgCsvWriter() {
        if (ppgCsvWriter != null) {
            try {
                ppgCsvWriter.flush();
                ppgCsvWriter.close();
                Log.i(TAG, "PPG CSV 닫기 완료");
            } catch (IOException e) {
                Log.e(TAG, "PPG CSV 닫기 오류", e);
            }
            ppgCsvWriter = null;
        }
    }

    // ======================== 음성 녹음 ========================

    /**
     * 음성 녹음 시작 (측정 시작 시 호출)
     */
    private void startVoiceRecording() {
        File clipDir = getClipDir();
        if (!clipDir.exists() && !clipDir.mkdirs()) {
            Log.e(TAG, "음성 녹음용 디렉토리 생성 실패");
            return;
        }
        File voiceFile = new File(clipDir, "voice.wav");
        voiceRecorder.start(voiceFile.getAbsolutePath());
    }

    /**
     * 음성 녹음 중지
     */
    private void stopVoiceRecording() {
        voiceRecorder.stop();
    }

    // ======================== meta.json 저장 ========================

    /**
     * 측정 종료 시 meta.json 저장
     */
    private void saveMetaJson(long durationMs) {
        if (clipId == null || participantId == null) return;

        File clipDir = getClipDir();
        File metaFile = new File(clipDir, "meta.json");

        long durationSec = durationMs / 1000;

        // null 값은 JSON에서 null로 직접 표기
        String emotionJson = (emotionLabel != null)
                ? "\"" + emotionLabel + "\""
                : "null";
        String veracityJson = (veracityLabel != null)
                ? "\"" + veracityLabel + "\""
                : "null";

        String json = "{\n"
                + "  \"clip_id\": \"" + clipId + "\",\n"
                + "  \"participant_id\": \"" + participantId + "\",\n"
                + "  \"part\": \"" + part + "\",\n"
                + "  \"emotion_label\": " + emotionJson + ",\n"
                + "  \"veracity_label\": " + veracityJson + ",\n"
                + "  \"duration_sec\": " + durationSec + "\n"
                + "}\n";

        try (FileWriter fw = new FileWriter(metaFile, false)) {
            fw.write(json);
            Log.i(TAG, "meta.json 저장 완료: " + metaFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "meta.json 저장 오류", e);
        }
    }

    // ======================== 카메라 / 녹화 세션 ========================

    private void openCamera() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) return;
            cameraManager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera open error: " + e.toString());
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            if (isRecording) {
                startRecordingSession();
            } else {
                startPreviewSession();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
        }
    };

    private void startRecordingSession() {
        try {
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this::analyzeImage, null);

            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(mediaRecorder.getSurface());
            builder.addTarget(surfaceHolder.getSurface());
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);

            cameraDevice.createCaptureSession(
                    Arrays.asList(mediaRecorder.getSurface(),
                            surfaceHolder.getSurface(),
                            imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, null);
                                mediaRecorder.start();
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Recording session error: " + e.toString());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(MainActivity.this, "세션 구성 실패", Toast.LENGTH_SHORT).show();
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access error: " + e.toString());
        }
    }

    private void startPreviewSession() {
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surfaceHolder.getSurface());
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            cameraDevice.createCaptureSession(
                    Collections.singletonList(surfaceHolder.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, null);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Preview session error: " + e.toString());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(MainActivity.this, "프리뷰 구성 실패", Toast.LENGTH_SHORT).show();
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Preview error: " + e.toString());
        }
    }

    private void prepareMediaRecorder() throws IOException {
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        String fn = "video_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".mp4";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, fn);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ppg_better");

        Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("동영상 파일 생성 실패");

        FileDescriptor fd = Objects.requireNonNull(
                getContentResolver().openFileDescriptor(uri, "w")).getFileDescriptor();
        mediaRecorder.setOutputFile(fd);
        mediaRecorder.setVideoEncodingBitRate(10_000_000);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoSize(1280, 720);
        mediaRecorder.setPreviewDisplay(surfaceHolder.getSurface());
        mediaRecorder.prepare();
    }

    /**
     * 측정 종료: PPG CSV, 음성 WAV 저장 후 meta.json 기록 → ChecklistActivity 복귀
     */
    private void stopRecording() {
        long durationMs = (startTime > 0) ? (System.currentTimeMillis() - startTime) : 0;

        try {
            captureSession.stopRepeating();
            captureSession.abortCaptures();
            mediaRecorder.stop();
            mediaRecorder.release();
            cameraDevice.close();
            cameraDevice = null;
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            greenSamples.clear();
            // finish() 후 화면을 벗어나므로 카메라 재시작 불필요
        } catch (Exception e) {
            Log.e(TAG, "Stop recording error: " + e.toString());
        }

        // PPG CSV 닫기
        closePpgCsvWriter();

        // 음성 녹음 중지 (WAV 저장은 VoiceRecorder 내부 스레드에서 완료)
        stopVoiceRecording();

        // meta.json 저장
        saveMetaJson(durationMs);

        // 저장 완료 Toast 후 ChecklistActivity로 복귀
        String clipDisplay = clipId != null ? clipId : "";
        Toast.makeText(this, getString(R.string.clip_saved, clipDisplay), Toast.LENGTH_SHORT).show();

        finish();
    }

    // ======================== 호흡 버튼 (기존 유지) ========================

    public void onToggleBreath(View view) {
        Button button = (Button) view;
        long now = System.currentTimeMillis();
        long timer = now - startTime;
        String state;

        if (!isBreathing) {
            state = "inhale";
            button.setText(R.string.measurement_button_end_wy);
            isBreathing = true;
        } else {
            state = "exhale";
            button.setText(R.string.measurement_button_start_wd);
            isBreathing = false;
        }

        Log.d(TAG, "Breath: " + timer + " : " + state);
    }

    // ======================== PPG 신호 분석 ========================

    private final Deque<Double> recentAverages = new ArrayDeque<>();
    private final List<Long> peakTimestamps = new ArrayList<>();

    private void addDataPoint(double value) {
        elapsedTime += 0.1f;
        if (entries.size() > 100) entries.remove(0);
        entries.add(new Entry(elapsedTime, (float) value));
        lineDataSet.notifyDataSetChanged();
        lineData.notifyDataChanged();
        lineChart.notifyDataSetChanged();
        lineChart.invalidate();
    }

    /**
     * 카메라 프레임 분석: Y 채널 평균 계산 → PPG CSV 저장 + 차트 업데이트 + 심박수 계산
     * 기존 분석 로직(피크 검출, BPM 계산)은 그대로 유지
     */
    private void analyzeImage(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;

        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        Image.Plane yPlane = planes[0];
        Image.Plane vPlane = planes[2];
        ByteBuffer yBuffer = yPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();
        int pixelStride = yPlane.getPixelStride();
        int rowStride = yPlane.getRowStride();

        int sum = 0;
        int count = 0;

        yBuffer.rewind();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int yIndex = row * rowStride + col * pixelStride;
                if (yIndex >= yBuffer.limit() || yIndex >= vBuffer.limit()) continue;
                int Y = yBuffer.get(yIndex) & 0xFF;
                sum += Y;
                count++;
            }
        }

        double average = sum / (double) count;

        // EMA 필터링 (WebSocket 전송용, 기존 로직 유지)
        if (filteredValue == null) {
            filteredValue = average;
        } else {
            filteredValue = EMA_ALPHA * average + (1 - EMA_ALPHA) * filteredValue;
        }

        long time = System.currentTimeMillis();

        // PPG CSV에 프레임별 raw 값 저장
        if (isRecording) {
            long elapsed = time - startTime;
            appendPpgFrame(elapsed, average);
        }

        // 차트 업데이트
        addDataPoint(average);

        // 피크 검출 (3-포인트 로컬 최대값, 기존 로직 유지)
        if (recentAverages.size() == 3) {
            recentAverages.removeFirst();
        }
        recentAverages.addLast(average);

        if (recentAverages.size() == 3) {
            Double[] arr = recentAverages.toArray(new Double[0]);
            double prev = arr[0];
            double curr = arr[1];
            double next = arr[2];

            if (curr > prev && curr > next) {
                long now = System.currentTimeMillis();
                if (peakTimestamps.isEmpty() || now - peakTimestamps.get(peakTimestamps.size() - 1) > 600) {
                    peakTimestamps.add(now);
                }
            }
        }

        if (greenSamples.remainingCapacity() == 0) {
            greenSamples.poll();
        }
        greenSamples.offer(average);

        image.close();

        // BPM 계산 및 표시 (기존 로직 유지)
        if (greenSamples.size() >= FFT_SIZE) {
            int bpm = computeHeartRate();

            if (!breathButton.isEnabled()) {
                breathButton.setEnabled(true);
            }

            runOnUiThread(() -> heartRateTextView.setText("HR: " + bpm + " BPM"));
        } else {
            int remainingSamples = FFT_SIZE - greenSamples.size();
            runOnUiThread(() ->
                    heartRateTextView.setText(String.format(Locale.getDefault(),
                            "%d more samples", remainingSamples)));
        }
    }

    // ======================== 심박수 계산 (기존 로직 그대로 유지) ========================

    private int computeHeartRate() {
        if (peakTimestamps.size() < 2) return 0;

        long now = System.currentTimeMillis();

        // 10초 이전 피크 제거
        while (peakTimestamps.size() > 1 && now - peakTimestamps.get(0) > 10_000) {
            peakTimestamps.remove(0);
        }

        if (peakTimestamps.size() < 2) return 0;

        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < peakTimestamps.size(); i++) {
            long diff = peakTimestamps.get(i) - peakTimestamps.get(i - 1);
            intervals.add(diff);
        }

        if (intervals.isEmpty()) return 0;

        intervals.sort(Long::compare);
        int n = intervals.size();
        long medianInterval;
        if (n % 2 == 0) {
            medianInterval = (intervals.get(n / 2 - 1) + intervals.get(n / 2)) / 2;
        } else {
            medianInterval = intervals.get(n / 2);
        }

        int bpm = (int) (60000.0 / medianInterval);

        if (bpm < 45 || bpm > 180) {
            Log.w(TAG, "BPM 범위 초과: " + bpm);
            return 0;
        }

        return bpm;
    }
}
