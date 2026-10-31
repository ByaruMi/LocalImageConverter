package com.synclab.local;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.Gravity;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * SyncLab: fully offline, Android-native audio/video sync editor.
 * Preview uses two MediaPlayers (video muted, audio independent) to avoid the
 * repeated seek/glitch issue. Export copies encoded samples into MP4, only
 * adjusting timestamps. No FFmpeg, ads, network, or video re-encoding.
 */
public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int PICK_VIDEO = 71;
    private static final int CREATE_MP4 = 72;
    private static final int MAX_OFFSET = 3000;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final int bg = Color.rgb(12, 18, 32);
    private final int panel = Color.rgb(26, 36, 54);
    private final int pale = Color.rgb(220, 232, 246);
    private final int dim = Color.rgb(150, 166, 188);
    private final int primary = Color.rgb(62, 154, 249);

    private Uri inputUri;
    private String inputName = "";
    private MediaPlayer videoPlayer;
    private MediaPlayer audioPlayer;
    private TextureView surfaceView;
    private Surface videoSurface;
    private TextView fileLabel, offsetText, currentTime, durationText, hintText, statusText;
    private Button playButton, compareButton, exportButton;
    private SeekBar playbackSeek, offsetSeek;
    private boolean preparedVideo = false, preparedAudio = false;
    private boolean playing = false, draggingTimeline = false;
    private boolean comparingOriginal = false, exporting = false;
    private boolean waitingForAudioSeek = false;
    private int offsetMs = 0;
    private int durationMs = 0;
    private int generation = 0;
    private Runnable pendingAudioStart;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (ready()) {
                try {
                    int p = Math.max(0, videoPlayer.getCurrentPosition());
                    if (!draggingTimeline) playbackSeek.setProgress(Math.min(durationMs, p));
                    currentTime.setText(formatTime(p));
                    if (playing && !videoPlayer.isPlaying()) {
                        // Do not repeatedly resync while playing; just detect the end.
                        if (durationMs > 0 && p >= durationMs - 250) stopPlayback(false);
                    }
                } catch (IllegalStateException ignored) { }
            }
            ui.postDelayed(this, 120);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        buildUi();
        ui.post(tick);
    }

    private int dp(int val) { return (int)(getResources().getDisplayMetrics().density * val + .5f); }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); }

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private Button button(String text, boolean accent) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent ? primary : panel));
        b.setMinHeight(dp(44));
        return b;
    }
    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }
    private void addSpace(LinearLayout l, int h) {
        View v = new View(this); l.addView(v, lp(-1, h));
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = label("SyncLab", 30, Color.WHITE, true);
        root.addView(title);
        TextView subtitle = label("영상과 소리 싱크 맞추기 · 오프라인", 13, dim, false);
        root.addView(subtitle);
        addSpace(root, 18);

        Button select = button("  영상 불러오기", true);
        root.addView(select, lp(-1, 52));
        select.setOnClickListener(v -> {
            if (exporting) return;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, PICK_VIDEO);
        });
        fileLabel = label("영상이 선택되지 않았습니다", 12, dim, false);
        fileLabel.setPadding(dp(3), dp(8), dp(3), dp(8));
        root.addView(fileLabel);

        surfaceView = new TextureView(this);
        surfaceView.setBackgroundColor(Color.BLACK);
        surfaceView.setKeepScreenOn(true);
        surfaceView.setSurfaceTextureListener(this);
        root.addView(surfaceView, lp(-1, 210));

        addSpace(root, 10);
        LinearLayout timeRow = row();
        currentTime = label("00:00.000", 12, pale, false);
        durationText = label("00:00.000", 12, dim, false);
        timeRow.addView(currentTime, new LinearLayout.LayoutParams(0, -2, 1));
        timeRow.addView(durationText);
        root.addView(timeRow);
        playbackSeek = new SeekBar(this);
        playbackSeek.setMax(1);
        root.addView(playbackSeek, lp(-1, 40));
        playbackSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { draggingTimeline = true; }
            @Override public void onProgressChanged(SeekBar bar, int val, boolean fromUser) {
                if (fromUser) currentTime.setText(formatTime(val));
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                draggingTimeline = false;
                jumpTo(bar.getProgress());
            }
        });

        LinearLayout controls = row();
        playButton = button("▶ 재생", true);
        playButton.setOnClickListener(v -> togglePlayback());
        compareButton = button("원본 비교: OFF", false);
        compareButton.setOnClickListener(v -> {
            comparingOriginal = !comparingOriginal;
            compareButton.setText(comparingOriginal ? "원본 비교: ON" : "원본 비교: OFF");
            updateOffsetUi();
            if (playing) resyncAudio();
        });
        controls.addView(playButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(compareButton, new LinearLayout.LayoutParams(0, dp(48), 1.5f));
        root.addView(controls);
        addSpace(root, 20);

        TextView adjTitle = label("오디오 지연 조정", 19, Color.WHITE, true);
        root.addView(adjTitle);
        offsetText = label("+0 ms", 35, primary, true);
        offsetText.setGravity(Gravity.CENTER_HORIZONTAL);
        offsetText.setPadding(0, dp(8), 0, dp(4));
        root.addView(offsetText);
        hintText = label("+ : 소리를 늦춤   |   − : 소리를 앞당김", 12, dim, false);
        hintText.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(hintText);
        offsetSeek = new SeekBar(this);
        offsetSeek.setMax(MAX_OFFSET * 2 / 10);
        offsetSeek.setProgress(MAX_OFFSET / 10);
        root.addView(offsetSeek, lp(-1, 55));
        offsetSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onProgressChanged(SeekBar bar, int val, boolean fromUser) {
                if (fromUser) {
                    offsetMs = val * 10 - MAX_OFFSET;
                    updateOffsetUi();
                }
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (playing) resyncAudio();
            }
        });
        LinearLayout tweaks = row();
        int[] steps = {-100, -10, 10, 100};
        for (int step : steps) {
            Button b = button((step > 0 ? "+" : "") + step + "ms", false);
            tweaks.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
            b.setOnClickListener(v -> {
                offsetMs = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, offsetMs + step));
                offsetSeek.setProgress((offsetMs + MAX_OFFSET) / 10);
                updateOffsetUi();
                if (playing) resyncAudio();
            });
        }
        root.addView(tweaks);
        addSpace(root, 24);
        exportButton = button("보정된 MP4 저장", true);
        exportButton.setOnClickListener(v -> requestExport());
        root.addView(exportButton, lp(-1, 55));
        statusText = label("네트워크·편집 엔진 불필요 · 원본 화질 유지", 12, dim, false);
        statusText.setGravity(Gravity.CENTER_HORIZONTAL);
        statusText.setPadding(dp(5), dp(10), dp(5), dp(4));
        root.addView(statusText);
        TextView disclaimer = label("오디오·비디오 코덱이 MP4와 호환되는 영상만 무손실 저장됩니다.\n영상 자체의 녹화 지연은 오디오 트랙 기준으로 조정됩니다.", 11, dim, false);
        disclaimer.setLineSpacing(dp(3), 1f);
        root.addView(disclaimer);
    }

    private String formatTime(int ms) {
        ms = Math.max(0, ms);
        int minutes = ms / 60000;
        int secs = (ms / 1000) % 60;
        int millis = ms % 1000;
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, secs, millis);
    }
    private void updateOffsetUi() {
        int val = comparingOriginal ? 0 : offsetMs;
        offsetText.setText(String.format(Locale.US, "%+d ms", val));
        hintText.setText(comparingOriginal ? "원본 싱크를 미리보기 중 (저장값은 유지됨)" : "+ : 소리를 늦춤   |   − : 소리를 앞당김");
    }
    private void status(String s) { statusText.setText(s); }
    private void notifyError(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); status(s); }
    private boolean ready() { return preparedVideo && preparedAudio && videoPlayer != null && audioPlayer != null; }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == PICK_VIDEO) openVideo(data.getData());
        else if (requestCode == CREATE_MP4) exportTo(data.getData());
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return "video";
    }

    private void releasePlayers() {
        generation++;
        cancelPendingAudio();
        playing = false;
        preparedVideo = preparedAudio = false;
        playButton.setText("▶ 재생");
        if (videoPlayer != null) { try { videoPlayer.release(); } catch (Exception ignored) { } videoPlayer = null; }
        if (audioPlayer != null) { try { audioPlayer.release(); } catch (Exception ignored) { } audioPlayer = null; }
    }

    private void openVideo(Uri uri) {
        releasePlayers();
        inputUri = uri;
        inputName = displayName(uri);
        fileLabel.setText(inputName);
        status("영상 준비 중…");
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, uri);
                String val = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                durationMs = val == null ? 0 : Integer.parseInt(val);
            } finally { retriever.release(); }
            playbackSeek.setMax(Math.max(1, durationMs));
            durationText.setText(formatTime(durationMs));
            currentTime.setText("00:00.000");
            videoPlayer = new MediaPlayer();
            audioPlayer = new MediaPlayer();
            int expected = generation;
            videoPlayer.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            videoPlayer.setVolume(0f, 0f);
            videoPlayer.setDataSource(this, uri);
            // The document picker can return before Android recreates the video surface.
            // Never bind a released SurfaceHolder ("The surface has been released").
            attachSurfaceIfAvailable();
            videoPlayer.setOnPreparedListener(mp -> { if (generation != expected) return; preparedVideo = true; checkReady(); });
            videoPlayer.setOnCompletionListener(mp -> stopPlayback(false));
            videoPlayer.setOnSeekCompleteListener(mp -> {
                if (generation != expected || !ready()) return;
                if (playing) resyncAudio();
            });
            videoPlayer.setOnErrorListener((mp, what, extra) -> { notifyError("영상 재생 오류 " + what); return true; });
            audioPlayer.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            audioPlayer.setDataSource(this, uri);
            audioPlayer.setOnPreparedListener(mp -> { if (generation != expected) return; preparedAudio = true; checkReady(); });
            audioPlayer.setOnSeekCompleteListener(mp -> {
                if (generation != expected) return;
                if (waitingForAudioSeek && playing && ready()) {
                    waitingForAudioSeek = false;
                    try { audioPlayer.start(); } catch (IllegalStateException ignored) { }
                }
            });
            audioPlayer.setOnErrorListener((mp, what, extra) -> { notifyError("오디오 재생 오류 " + what); return true; });
            videoPlayer.prepareAsync();
            audioPlayer.prepareAsync();
        } catch (Exception e) {
            releasePlayers();
            notifyError("영상을 열지 못했습니다: " + e.getMessage());
        }
    }

    private void checkReady() {
        if (ready()) {
            status(videoSurface == null ? "재생 준비 완료 · 화면이 연결될 때까지 기다리는 중…"
                    : "재생 준비 완료 · 싱크를 조정한 뒤 MP4로 저장하세요.");
        }
    }
    private void togglePlayback() {
        if (!ready()) { notifyError("먼저 영상을 불러와 주세요."); return; }
        if (videoSurface == null || !videoSurface.isValid()) {
            notifyError("영상 출력 화면을 준비 중입니다. 다시 재생해 주세요.");
            return;
        }
        if (playing) stopPlayback(true);
        else {
            try {
                if (durationMs > 0 && videoPlayer.getCurrentPosition() >= durationMs - 100) {
                    videoPlayer.seekTo(0, MediaPlayer.SEEK_CLOSEST_SYNC);
                }
                playing = true;
                playButton.setText("❚❚ 일시정지");
                videoPlayer.start();
                resyncAudio();
            } catch (IllegalStateException e) { notifyError("재생을 시작하지 못했습니다."); }
        }
    }
    private void cancelPendingAudio() {
        if (pendingAudioStart != null) { ui.removeCallbacks(pendingAudioStart); pendingAudioStart = null; }
        waitingForAudioSeek = false;
    }
    private void stopPlayback(boolean pause) {
        cancelPendingAudio();
        playing = false;
        playButton.setText("▶ 재생");
        if (videoPlayer != null && preparedVideo) { try { if (videoPlayer.isPlaying()) videoPlayer.pause(); } catch (Exception ignored) {} }
        if (audioPlayer != null && preparedAudio) { try { if (audioPlayer.isPlaying()) audioPlayer.pause(); } catch (Exception ignored) {} }
        if (!pause) status("재생 종료");
    }
    private int effectiveOffset() { return comparingOriginal ? 0 : offsetMs; }

    private void jumpTo(int position) {
        if (!ready()) return;
        cancelPendingAudio();
        try {
            if (audioPlayer.isPlaying()) audioPlayer.pause();
            videoPlayer.seekTo(position, MediaPlayer.SEEK_CLOSEST_SYNC);
            if (!playing) {
                int ap = Math.max(0, position - effectiveOffset());
                audioPlayer.seekTo(Math.min(durationMs, ap), MediaPlayer.SEEK_CLOSEST);
            }
        } catch (IllegalStateException ignored) { }
    }

    /** Called only at start / user seek / user offset change, never in the frame ticker. */
    private void resyncAudio() {
        if (!ready()) return;
        cancelPendingAudio();
        try { if (audioPlayer.isPlaying()) audioPlayer.pause(); } catch (Exception ignored) { }
        if (!playing) return;
        try {
            int target = videoPlayer.getCurrentPosition() - effectiveOffset();
            if (target >= durationMs) return; // audio naturally ends before video
            if (target < 0) {
                final int expectedGeneration = generation;
                final int delay = -target;
                pendingAudioStart = () -> {
                    pendingAudioStart = null;
                    if (generation != expectedGeneration || !playing || !ready()) return;
                    resyncAudio();
                };
                ui.postDelayed(pendingAudioStart, Math.max(10, delay));
            } else {
                waitingForAudioSeek = true;
                audioPlayer.seekTo(target, MediaPlayer.SEEK_CLOSEST);
            }
        } catch (IllegalStateException e) { notifyError("오디오 재동기화 실패"); }
    }

    private void requestExport() {
        if (inputUri == null || !ready()) { notifyError("먼저 영상을 불러와 주세요."); return; }
        if (exporting) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/mp4");
        String suffix = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        i.putExtra(Intent.EXTRA_TITLE, "SyncLab_" + suffix + ".mp4");
        startActivityForResult(i, CREATE_MP4);
    }

    private void exportTo(Uri outputUri) {
        if (exporting || inputUri == null) return;
        stopPlayback(true);
        exporting = true;
        exportButton.setEnabled(false);
        status("MP4 저장 시작…");
        final Uri source = inputUri;
        final int shift = offsetMs; // save chosen adjustment even in original-compare mode
        new Thread(() -> {
            boolean succeeded = false;
            String message;
            try {
                copyTracks(source, outputUri, shift);
                succeeded = true;
                message = "저장 완료! 선택한 폴더에서 MP4를 확인하세요.";
            } catch (Exception e) {
                message = "저장 실패: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                try { getContentResolver().delete(outputUri, null, null); } catch (Exception ignored) { }
            }
            final String finalMessage = message;
            final boolean success = succeeded;
            runOnUiThread(() -> {
                exporting = false;
                exportButton.setEnabled(true);
                status(finalMessage);
                Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show();
                if (success) fileLabel.setText(inputName + "  ✓ 저장됨");
            });
        }, "synclab-export").start();
    }

    /** MP4 mux: no decode/encode; audio PTS shifted by the requested milliseconds. */
    private void copyTracks(Uri source, Uri dest, int shiftMilliseconds) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        ParcelFileDescriptor outFd = null;
        boolean muxerStarted = false;
        try {
            extractor.setDataSource(this, source, null);
            int trackCount = extractor.getTrackCount();
            int[] map = new int[trackCount];
            int[] kinds = new int[trackCount];
            for (int i = 0; i < trackCount; i++) { map[i] = -1; kinds[i] = 0; }
            outFd = getContentResolver().openFileDescriptor(dest, "rw");
            if (outFd == null) throw new IOException("저장 위치를 열 수 없습니다.");
            muxer = new MediaMuxer(outFd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videos = 0, audios = 0;
            long maxDurationUs = 0;
            for (int i = 0; i < trackCount; i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                boolean video = mime.startsWith("video/");
                boolean audio = mime.startsWith("audio/");
                if (video && videos == 0) {
                    if (!(mime.equals("video/avc") || mime.equals("video/hevc") || mime.equals("video/mp4v-es") || mime.equals("video/3gpp") || mime.equals("video/av01")))
                        throw new IOException("영상 코덱 " + mime + "은 MP4 무손실 저장을 지원하지 않습니다.");
                    map[i] = muxer.addTrack(f);
                    kinds[i] = 1;
                    if (f.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(f.getInteger(MediaFormat.KEY_ROTATION));
                    videos++;
                } else if (audio && audios == 0) {
                    if (!(mime.equals("audio/mp4a-latm") || mime.equals("audio/3gpp") || mime.equals("audio/amr-wb") || mime.equals("audio/mpeg") || mime.equals("audio/opus")))
                        throw new IOException("오디오 코덱 " + mime + "은 MP4 무손실 저장을 지원하지 않습니다.");
                    map[i] = muxer.addTrack(f);
                    kinds[i] = 2;
                    audios++;
                }
                if (f.containsKey(MediaFormat.KEY_DURATION)) maxDurationUs = Math.max(maxDurationUs, f.getLong(MediaFormat.KEY_DURATION));
            }
            if (videos == 0) throw new IOException("영상 트랙이 없습니다.");
            if (audios == 0) throw new IOException("오디오 트랙이 없습니다.");
            // Select only the video and audio streams (no subtitle/timed metadata).
            for (int i = 0; i < trackCount; i++) if (map[i] >= 0) extractor.selectTrack(i);
            muxer.start();
            muxerStarted = true;
            ByteBuffer buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long shiftUs = shiftMilliseconds * 1000L;
            long lastProgress = 0;
            while (true) {
                int sourceTrack = extractor.getSampleTrackIndex();
                if (sourceTrack < 0) break;
                if (map[sourceTrack] < 0) { extractor.advance(); continue; }
                long pts = extractor.getSampleTime();
                if (pts < 0) break;
                long adjusted = pts + (kinds[sourceTrack] == 2 ? shiftUs : 0);
                if (adjusted >= 0) {
                    buffer.clear();
                    int bytes = extractor.readSampleData(buffer, 0);
                    if (bytes < 0) break;
                    if (bytes >= buffer.capacity()) throw new IOException("영상 샘플이 너무 큽니다.");
                    info.offset = 0;
                    info.size = bytes;
                    info.presentationTimeUs = adjusted;
                    info.flags = extractor.getSampleFlags();
                    muxer.writeSampleData(map[sourceTrack], buffer, info);
                }
                if (pts - lastProgress > 350000) {
                    lastProgress = pts;
                    final int pct = maxDurationUs > 0 ? (int)Math.min(99L, pts * 100L / maxDurationUs) : 0;
                    runOnUiThread(() -> status("MP4 저장 중… " + pct + "%"));
                }
                extractor.advance();
            }
            muxer.stop();
            muxerStarted = false;
        } finally {
            extractor.release();
            if (muxer != null) {
                if (muxerStarted) { try { muxer.stop(); } catch (Exception ignored) { } }
                try { muxer.release(); } catch (Exception ignored) { }
            }
            if (outFd != null) try { outFd.close(); } catch (Exception ignored) { }
        }
    }

    /** Attach the video output only when there is a valid, live SurfaceTexture. */
    private void attachSurfaceIfAvailable() {
        if (videoPlayer == null || videoSurface == null || !videoSurface.isValid()) return;
        try {
            videoPlayer.setSurface(videoSurface);
        } catch (IllegalStateException e) {
            status("영상 출력 연결 실패 · 영상을 다시 선택해 주세요.");
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (videoSurface != null) videoSurface.release();
        videoSurface = new Surface(texture);
        attachSurfaceIfAvailable();
        checkReady();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        if (playing) stopPlayback(true);
        if (videoPlayer != null) {
            try { videoPlayer.setSurface(null); } catch (IllegalStateException ignored) { }
        }
        if (videoSurface != null) { videoSurface.release(); videoSurface = null; }
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
    @Override protected void onPause() { super.onPause(); if (playing) stopPlayback(true); }
    @Override protected void onDestroy() {
        ui.removeCallbacks(tick);
        releasePlayers();
        if (videoSurface != null) { videoSurface.release(); videoSurface = null; }
        super.onDestroy();
    }
}
