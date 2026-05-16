// VoiceRecorder.java
// AudioRecord를 사용하여 16kHz mono PCM 16-bit 음성을 녹음하고 WAV 파일로 저장하는 클래스

package com.example.projekcik;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class VoiceRecorder {

    private static final String TAG = "VoiceRecorder";

    // 오디오 설정 상수
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BYTES_PER_SAMPLE = 2; // PCM 16-bit = 2 bytes

    private AudioRecord audioRecord;
    private Thread recordingThread;
    private volatile boolean isRecording = false;
    private String outputPath;

    /**
     * 녹음 시작
     * @param outputPath WAV 파일을 저장할 전체 경로
     */
    public void start(String outputPath) {
        if (isRecording) {
            Log.w(TAG, "이미 녹음 중입니다.");
            return;
        }
        this.outputPath = outputPath;

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "AudioRecord 버퍼 크기 계산 실패");
            return;
        }
        // 버퍼를 최소 크기의 4배로 설정하여 안정성 확보
        bufferSize = bufferSize * 4;

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 초기화 실패");
            audioRecord.release();
            audioRecord = null;
            return;
        }

        isRecording = true;
        audioRecord.startRecording();

        final int finalBufferSize = bufferSize;
        recordingThread = new Thread(() -> writeAudioToFile(finalBufferSize), "VoiceRecorderThread");
        recordingThread.start();

        Log.i(TAG, "녹음 시작: " + outputPath);
    }

    /**
     * 녹음 중지 및 WAV 파일 저장
     */
    public void stop() {
        if (!isRecording) {
            Log.w(TAG, "녹음 중이 아닙니다.");
            return;
        }

        isRecording = false;

        if (recordingThread != null) {
            try {
                recordingThread.join(3000); // 최대 3초 대기
            } catch (InterruptedException e) {
                Log.e(TAG, "녹음 스레드 종료 대기 중 인터럽트", e);
                Thread.currentThread().interrupt();
            }
            recordingThread = null;
        }

        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException e) {
                Log.e(TAG, "AudioRecord 중지 오류", e);
            }
            audioRecord.release();
            audioRecord = null;
        }

        Log.i(TAG, "녹음 중지 완료");
    }

    /**
     * 별도 스레드에서 오디오 데이터를 읽어 임시 PCM 파일로 저장 후 WAV 헤더 추가
     */
    private void writeAudioToFile(int bufferSize) {
        // 임시 PCM 파일 경로
        String pcmPath = outputPath + ".pcm";
        File pcmFile = new File(pcmPath);
        File wavFile = new File(outputPath);

        // 출력 디렉토리 생성
        File parentDir = wavFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                Log.e(TAG, "디렉토리 생성 실패: " + parentDir.getAbsolutePath());
                return;
            }
        }

        byte[] buffer = new byte[bufferSize];
        long totalBytesRead = 0;

        // PCM 데이터 기록
        try (FileOutputStream pcmOutputStream = new FileOutputStream(pcmFile)) {
            while (isRecording) {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    pcmOutputStream.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "AudioRecord 읽기 오류: ERROR_INVALID_OPERATION");
                    break;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "PCM 파일 쓰기 오류", e);
            return;
        }

        // PCM → WAV 변환 (헤더 추가)
        try {
            writePcmToWav(pcmFile, wavFile, totalBytesRead);
            // 임시 PCM 파일 삭제
            if (!pcmFile.delete()) {
                Log.w(TAG, "임시 PCM 파일 삭제 실패: " + pcmPath);
            }
            Log.i(TAG, "WAV 저장 완료: " + outputPath + " (" + totalBytesRead + " bytes PCM)");
        } catch (IOException e) {
            Log.e(TAG, "WAV 파일 변환 오류", e);
        }
    }

    /**
     * PCM 파일을 읽어 WAV 헤더를 붙여 WAV 파일로 저장
     */
    private void writePcmToWav(File pcmFile, File wavFile, long pcmDataBytes) throws IOException {
        int channels = 1; // mono
        int byteRate = SAMPLE_RATE * channels * BYTES_PER_SAMPLE;
        int blockAlign = channels * BYTES_PER_SAMPLE;

        // WAV 파일 쓰기
        try (FileOutputStream wavOut = new FileOutputStream(wavFile)) {
            // RIFF 헤더 (44 bytes)
            byte[] header = buildWavHeader(pcmDataBytes, byteRate, blockAlign);
            wavOut.write(header);

            // PCM 데이터 복사
            byte[] readBuffer = new byte[8192];
            try (java.io.FileInputStream pcmIn = new java.io.FileInputStream(pcmFile)) {
                int bytesRead;
                while ((bytesRead = pcmIn.read(readBuffer)) != -1) {
                    wavOut.write(readBuffer, 0, bytesRead);
                }
            }
        }
    }

    /**
     * WAV RIFF 헤더 44바이트 빌드
     */
    private byte[] buildWavHeader(long pcmDataBytes, int byteRate, int blockAlign) {
        long totalDataLen = pcmDataBytes + 36; // 헤더 제외 데이터 크기
        byte[] header = new byte[44];

        // RIFF 청크
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        // 전체 파일 크기 - 8 (little-endian)
        header[4] = (byte)(totalDataLen & 0xff);
        header[5] = (byte)((totalDataLen >> 8) & 0xff);
        header[6] = (byte)((totalDataLen >> 16) & 0xff);
        header[7] = (byte)((totalDataLen >> 24) & 0xff);
        // WAVE
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';

        // fmt 청크
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        // fmt 청크 크기 = 16 (PCM)
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        // 오디오 포맷 = 1 (PCM)
        header[20] = 1; header[21] = 0;
        // 채널 수 = 1 (mono)
        header[22] = 1; header[23] = 0;
        // 샘플 레이트 (little-endian)
        header[24] = (byte)(SAMPLE_RATE & 0xff);
        header[25] = (byte)((SAMPLE_RATE >> 8) & 0xff);
        header[26] = (byte)((SAMPLE_RATE >> 16) & 0xff);
        header[27] = (byte)((SAMPLE_RATE >> 24) & 0xff);
        // 바이트 레이트 (little-endian)
        header[28] = (byte)(byteRate & 0xff);
        header[29] = (byte)((byteRate >> 8) & 0xff);
        header[30] = (byte)((byteRate >> 16) & 0xff);
        header[31] = (byte)((byteRate >> 24) & 0xff);
        // 블록 정렬
        header[32] = (byte)(blockAlign & 0xff);
        header[33] = (byte)((blockAlign >> 8) & 0xff);
        // 비트 심도 = 16
        header[34] = 16; header[35] = 0;

        // data 청크
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        // 데이터 크기 (little-endian)
        header[40] = (byte)(pcmDataBytes & 0xff);
        header[41] = (byte)((pcmDataBytes >> 8) & 0xff);
        header[42] = (byte)((pcmDataBytes >> 16) & 0xff);
        header[43] = (byte)((pcmDataBytes >> 24) & 0xff);

        return header;
    }

    public boolean isRecording() {
        return isRecording;
    }
}
