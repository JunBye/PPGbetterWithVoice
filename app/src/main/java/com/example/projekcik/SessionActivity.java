// SessionActivity.java
// 앱 진입점: 참가자 ID 입력 또는 기존 참가자 드롭다운 선택 후 세션 시작

package com.example.projekcik;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SessionActivity extends Activity {

    private static final String TAG = "SessionActivity";
    private static final int REQUEST_PERMISSIONS = 100;

    // PPG 데이터 저장 루트 경로: Downloads/ppg_data/
    public static final String PPG_ROOT_DIR = "ppg_data";

    // Intent Extra 키 상수
    public static final String EXTRA_PARTICIPANT_ID = "participant_id";

    private EditText editParticipantId;
    private Spinner spinnerParticipants;
    private Button buttonStartSession;

    private List<String> participantList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session);

        editParticipantId = findViewById(R.id.editParticipantId);
        spinnerParticipants = findViewById(R.id.spinnerParticipants);
        buttonStartSession = findViewById(R.id.buttonStartSession);

        // 세션 시작 버튼 클릭 리스너
        buttonStartSession.setOnClickListener(v -> onStartSession());

        // 권한 확인 후 기존 참가자 목록 로드
        checkAndRequestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 화면 복귀 시 참가자 목록 갱신
        loadExistingParticipants();
    }

    /**
     * 필요한 권한 요청 (카메라, 마이크, 저장소)
     */
    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);
        }
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    REQUEST_PERMISSIONS);
        } else {
            loadExistingParticipants();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            // 카메라, 마이크 권한은 필수
            boolean cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
            boolean audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;

            if (!cameraGranted || !audioGranted) {
                Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show();
            }
            loadExistingParticipants();
        }
    }

    /**
     * Downloads/ppg_data/ 하위 폴더 목록을 읽어 스피너에 표시
     */
    private void loadExistingParticipants() {
        participantList.clear();
        participantList.add(getString(R.string.spinner_hint)); // 힌트 항목

        File ppgRoot = getPpgRootDir();
        if (ppgRoot.exists() && ppgRoot.isDirectory()) {
            File[] subDirs = ppgRoot.listFiles(File::isDirectory);
            if (subDirs != null) {
                Arrays.sort(subDirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File dir : subDirs) {
                    participantList.add(dir.getName());
                }
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, participantList) {
            @Override
            public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                android.view.View view = super.getView(position, convertView, parent);
                ((android.widget.TextView) view).setTextColor(android.graphics.Color.parseColor("#222222"));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerParticipants.setAdapter(adapter);

        // 드롭다운 선택 시 EditText에 자동 입력
        spinnerParticipants.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) {
                    // 힌트(0번) 제외, 실제 참가자 ID 선택 시 EditText에 채우기
                    String selectedId = participantList.get(position);
                    editParticipantId.setText(selectedId);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 아무것도 선택되지 않았을 때 처리 불필요
            }
        });

        Log.d(TAG, "참가자 목록 로드 완료: " + (participantList.size() - 1) + "명");
    }

    /**
     * 세션 시작 버튼 처리: 참가자 ID 검증 후 ChecklistActivity로 이동
     */
    private void onStartSession() {
        String participantId = editParticipantId.getText().toString().trim();

        if (participantId.isEmpty()) {
            Toast.makeText(this, getString(R.string.enter_participant_id), Toast.LENGTH_SHORT).show();
            return;
        }

        // 공백이나 특수문자 방지 (파일 경로 안전성)
        if (!participantId.matches("[a-zA-Z0-9_\\-]+")) {
            Toast.makeText(this, getString(R.string.invalid_participant_id), Toast.LENGTH_SHORT).show();
            return;
        }

        // ChecklistActivity로 이동
        Intent intent = new Intent(this, ChecklistActivity.class);
        intent.putExtra(EXTRA_PARTICIPANT_ID, participantId);
        startActivity(intent);
    }

    /**
     * Downloads/ppg_data/ 경로 반환
     */
    public static File getPpgRootDir() {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return new File(downloadsDir, PPG_ROOT_DIR);
    }
}
