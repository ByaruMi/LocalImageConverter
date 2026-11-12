package app.yoinkpocket.local

import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale

object DownloadState {
    @Volatile var running = false
    @Volatile var cancelRequested = false
    @Volatile var percent = 0
    @Volatile var message = "영상 링크를 입력하세요."
}
class DownloadService : Service() {
    companion object {
        const val ACTION_START = "yoink.START"
        const val ACTION_CANCEL = "yoink.CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_FORMAT = "format"
        private const val CHANNEL = "downloads"
        private const val PROCESS = "yoink-single-download"
        private const val NOTIFICATION_ID = 2001
    }
    @Volatile private var active = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "다운로드 진행", NotificationManager.IMPORTANCE_LOW)
        )
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            DownloadState.cancelRequested = true
            DownloadState.message = "다운로드 취소 중..."
            try { YoutubeDL.getInstance().destroyProcessById(PROCESS) } catch (_: Exception) {}
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || active) return START_NOT_STICKY
        val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val format = intent.getIntExtra(EXTRA_FORMAT,0).coerceIn(0,4)
        active = true
        DownloadState.running = true
        DownloadState.cancelRequested = false
        DownloadState.percent = 0
        DownloadState.message = "엔진 초기화 중..."
        startForeground(NOTIFICATION_ID, notification("다운로드 준비 중",0))
        Thread({ perform(url,format) }, "yoink-download").start()
        return START_NOT_STICKY
    }
    private fun perform(url: String, format: Int) {
        var stage: File? = null
        try {
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
            if (DownloadState.cancelRequested) throw InterruptedException()
            val base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IllegalStateException("임시 폴더를 사용할 수 없습니다.")
            stage = File(base,"yoink-staging/" + System.currentTimeMillis())
            if (!stage.mkdirs()) throw IllegalStateException("폴더 생성 실패")
            val req = YoutubeDLRequest(url)
            req.addOption("--no-playlist")
            req.addOption("--no-mtime")
            req.addOption("--no-warnings")
            req.addOption("-o", File(stage,"%(title).120B_%(id)s.%(ext)s").absolutePath)
            if (format == 4) {
                req.addOption("-f","bestaudio/best")
                req.addOption("-x")
                req.addOption("--audio-format","mp3")
                req.addOption("--audio-quality","0")
            } else {
                val cap = when(format) { 1 -> 1080; 2 -> 720; 3 -> 480; else -> 0 }
                val selection = if (cap == 0) "bv*+ba/b"
                  else "bv*[height<=" + cap + "]+ba/b[height<=" + cap + "]/best[height<=" + cap + "]"
                req.addOption("-f",selection)
                req.addOption("--merge-output-format","mp4")
            }
            DownloadState.message = "영상 정보를 가져오는 중..."
            YoutubeDL.getInstance().execute(req, PROCESS, { p: Float, eta: Long, _: String ->
                if (!DownloadState.cancelRequested) {
                    val amount = p.toInt().coerceIn(0,100)
                    DownloadState.percent = amount
                    DownloadState.message = "다운로드 " + amount + "% (" +
                        (if (eta > 0) eta.toString() + "초 남음" else "처리 중") + ")"
                    if (amount % 10 == 0) notifyProgress(DownloadState.message,amount)
                }
            })
            if (DownloadState.cancelRequested) throw InterruptedException()
            DownloadState.message = "휴대폰 갤러리에 저장 중..."
            val exts = if (format == 4) setOf("mp3") else setOf("mp4","mkv","webm","mov","m4v")
            val found = stage.listFiles()?.filter {
                it.isFile && it.extension.lowercase(Locale.ROOT) in exts
            }?.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("다운로드 결과 파일을 찾지 못했습니다.")
            val result = save(found, format == 4)
            getSharedPreferences("last",MODE_PRIVATE).edit()
                .putString("uri",result.first.toString()).putString("mime",result.second).apply()
            DownloadState.percent = 100
            DownloadState.message = "완료: " + found.name
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID+1, notification("저장 완료",100))
        } catch (e: Exception) {
            DownloadState.message = if (DownloadState.cancelRequested || e is InterruptedException)
                "다운로드 취소됨"
            else "실패: " + (e.localizedMessage ?: "알 수 없는 오류").take(200)
            Log.e("YoinkPocket","Download failure",e)
        } finally {
            stage?.deleteRecursively()
            DownloadState.running = false
            DownloadState.cancelRequested = false
            active = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    private fun save(file: File, audio: Boolean): Pair<Uri,String> {
        val ext = file.extension.lowercase(Locale.ROOT)
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            else -> "video/mp4"
        }
        val destination = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                          else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val location = if (audio) Environment.DIRECTORY_MUSIC + "/YoinkPocket"
                       else Environment.DIRECTORY_MOVIES + "/YoinkPocket"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,file.name)
            put(MediaStore.MediaColumns.MIME_TYPE,mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,location)
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        val uri = contentResolver.insert(destination,values)
            ?: throw IllegalStateException("저장소에 파일을 만들 수 없어요.")
        try {
            val stream = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("저장 파일을 열 수 없어요.")
            stream.use { output -> file.inputStream().use { input -> input.copyTo(output) } }
            contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING,0)
            },null,null)
        } catch (e: Exception) {
            contentResolver.delete(uri,null,null)
            throw e
        }
        return uri to mime
    }
    private fun notification(text: String, amount: Int): Notification {
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Yoink Pocket")
            .setContentText(text.take(120))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
        if (DownloadState.running) {
            builder.setProgress(100,amount,amount == 0).setOngoing(true)
        }
        return builder.build()
    }
    private fun notifyProgress(text: String,amount: Int) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID,notification(text,amount))
    }
}
