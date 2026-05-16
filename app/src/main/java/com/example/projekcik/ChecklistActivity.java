// ChecklistActivity.java
// Part A (8감정) + Part B (true×5, fake×5) 체크리스트 화면
// 이미 녹화된 클립은 버튼에 ✅ 표시 (재촬영 가능)

package com.example.projekcik;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class ChecklistActivity extends Activity {

    private static final String TAG = "ChecklistActivity";

    // Intent Extra 키 상수
    public static final String EXTRA_PART = "part";
    public static final String EXTRA_EMOTION_LABEL = "emotion_label";
    public static final String EXTRA_VERACITY_LABEL = "veracity_label";
    public static final String EXTRA_CLIP_ID = "clip_id";

    // Part A 감정 목록
    private static final String[] EMOTIONS = {
            "happy", "sad", "angry", "fear",
            "disgust", "surprise", "neutral", "contempt"
    };

    // Part B 조건
    private static final String[] VERACITIES = {"true", "fake"};
    private static final int CLIPS_PER_VERACITY = 5;

    private String participantId;

    // 버튼 참조 보관 (onResume에서 체크 상태 갱신용)
    private Button[] partAButtons;
    private Button[][] partBButtons; // [veracity_idx][q_idx]

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        participantId = getIntent().getStringExtra(SessionActivity.EXTRA_PARTICIPANT_ID);
        if (participantId == null || participantId.isEmpty()) {
            Toast.makeText(this, "참가자 ID가 없습니다.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 레이아웃 설정
        setContentView(R.layout.activity_checklist);

        // 참가자 ID 표시
        TextView tvParticipantId = findViewById(R.id.tvChecklistParticipantId);
        tvParticipantId.setText(getString(R.string.participant_label, participantId));

        // 버튼 그리드 생성
        buildPartAButtons();
        buildPartBButtons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 화면 복귀 시(측정 완료 후) 완료된 클립 표시 갱신
        refreshCompletedStatus();
    }

    /**
     * Part A 버튼 그리드 생성 (8개 감정)
     */
    private void buildPartAButtons() {
        GridLayout gridPartA = findViewById(R.id.gridPartA);
        gridPartA.removeAllViews();
        gridPartA.setColumnCount(2);

        partAButtons = new Button[EMOTIONS.length];

        for (int i = 0; i < EMOTIONS.length; i++) {
            final String emotion = EMOTIONS[i];
            // clip_id: A_{emotion}_01
            final String clipId = "A_" + emotion + "_01";

            Button btn = new Button(this);
            btn.setText(emotion);
            btn.setTextSize(14f);

            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(8, 8, 8, 8);
            btn.setLayoutParams(params);

            btn.setOnClickListener(v -> launchMeasurement("A", emotion, null, clipId));

            partAButtons[i] = btn;
            gridPartA.addView(btn);
        }
    }

    /**
     * Part B 버튼 그리드 생성 (true×5, fake×5)
     */
    private void buildPartBButtons() {
        GridLayout gridPartB = findViewById(R.id.gridPartB);
        gridPartB.removeAllViews();
        gridPartB.setColumnCount(2);

        partBButtons = new Button[VERACITIES.length][CLIPS_PER_VERACITY];

        for (int vi = 0; vi < VERACITIES.length; vi++) {
            final String veracity = VERACITIES[vi];

            // 열 헤더 라벨 (true / fake)
            TextView header = new TextView(this);
            header.setText(veracity.toUpperCase());
            header.setTextSize(14f);
            header.setTypeface(null, Typeface.BOLD);
            header.setGravity(Gravity.CENTER);

            GridLayout.LayoutParams headerParams = new GridLayout.LayoutParams();
            headerParams.width = 0;
            headerParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            headerParams.columnSpec = GridLayout.spec(vi, 1f);
            // true: row0, fake: row0 (같은 행에 헤더 배치)
            headerParams.rowSpec = GridLayout.spec(0);
            headerParams.setMargins(8, 8, 8, 4);
            header.setLayoutParams(headerParams);
            gridPartB.addView(header);

            for (int qi = 0; qi < CLIPS_PER_VERACITY; qi++) {
                final int qNum = qi + 1;
                // clip_id: B_{true/fake}_Q{1~5}
                final String clipId = "B_" + veracity + "_Q" + qNum;

                Button btn = new Button(this);
                btn.setText("Q" + qNum);
                btn.setTextSize(14f);

                GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                params.width = 0;
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                params.columnSpec = GridLayout.spec(vi, 1f);
                params.rowSpec = GridLayout.spec(qi + 1); // 헤더 다음 행부터
                params.setMargins(8, 4, 8, 4);
                btn.setLayoutParams(params);

                btn.setOnClickListener(v -> launchMeasurement("B", null, veracity, clipId));

                partBButtons[vi][qi] = btn;
                gridPartB.addView(btn);
            }
        }
    }

    /**
     * Downloads/ppg_data/{participantId}/ 폴더를 스캔하여 완료된 클립 버튼에 ✅ 표시
     * 버튼은 비활성화하지 않음 (재촬영 가능)
     */
    private void refreshCompletedStatus() {
        if (partAButtons == null || partBButtons == null) return;

        Set<String> completedClips = getCompletedClipIds();

        // Part A 갱신
        for (int i = 0; i < EMOTIONS.length; i++) {
            String clipId = "A_" + EMOTIONS[i] + "_01";
            String baseLabel = EMOTIONS[i];
            if (completedClips.contains(clipId)) {
                partAButtons[i].setText(baseLabel + " ✅");
            } else {
                partAButtons[i].setText(baseLabel);
            }
        }

        // Part B 갱신
        for (int vi = 0; vi < VERACITIES.length; vi++) {
            for (int qi = 0; qi < CLIPS_PER_VERACITY; qi++) {
                int qNum = qi + 1;
                String clipId = "B_" + VERACITIES[vi] + "_Q" + qNum;
                String baseLabel = "Q" + qNum;
                if (completedClips.contains(clipId)) {
                    partBButtons[vi][qi].setText(baseLabel + " ✅");
                } else {
                    partBButtons[vi][qi].setText(baseLabel);
                }
            }
        }
    }

    /**
     * 저장 폴더를 스캔하여 이미 완료된 clip_id 집합 반환
     * 완료 기준: Downloads/ppg_data/{participantId}/{clipId}/ 폴더에 ppg.csv가 존재
     */
    private Set<String> getCompletedClipIds() {
        Set<String> completed = new HashSet<>();

        File participantDir = new File(SessionActivity.getPpgRootDir(), participantId);
        if (!participantDir.exists() || !participantDir.isDirectory()) {
            return completed;
        }

        File[] clipDirs = participantDir.listFiles(File::isDirectory);
        if (clipDirs == null) return completed;

        for (File clipDir : clipDirs) {
            File ppgCsv = new File(clipDir, "ppg.csv");
            if (ppgCsv.exists()) {
                completed.add(clipDir.getName());
            }
        }

        return completed;
    }

    /**
     * 측정 화면(MainActivity)으로 이동
     */
    private void launchMeasurement(String part, String emotionLabel,
                                   String veracityLabel, String clipId) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(SessionActivity.EXTRA_PARTICIPANT_ID, participantId);
        intent.putExtra(EXTRA_PART, part);
        intent.putExtra(EXTRA_EMOTION_LABEL, emotionLabel);   // Part B이면 null
        intent.putExtra(EXTRA_VERACITY_LABEL, veracityLabel); // Part A이면 null
        intent.putExtra(EXTRA_CLIP_ID, clipId);
        startActivity(intent);
    }
}
