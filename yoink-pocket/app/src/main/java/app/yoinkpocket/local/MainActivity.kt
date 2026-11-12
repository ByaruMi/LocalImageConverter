package app.yoinkpocket.local

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface

class MainActivity : Activity() {
    private lateinit var urlInput: EditText
    private lateinit var options: Spinner
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var cancel: Button
    private lateinit var open: Button
    private val handler = Handler(Looper.getMainLooper())
    private val updater = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 500L) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            setBackgroundColor(Color.rgb(247,248,252))
        }
        val scroller = ScrollView(this).apply { addView(root) }
        setContentView(scroller)
        if (Build.VERSION.SDK_INT >= 35) {
            scroller.setOnApplyWindowInsetsListener { view, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }
        fun add(v: View) { root.addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 15 }) }
        val title = TextView(this).apply {
            text = "Yoink Pocket"
            textSize = 28f
            setTextColor(Color.rgb(23,34,62))
            setTypeface(null, Typeface.BOLD)
        }
        add(title)
        add(TextView(this).apply {
            text = "영상과 오디오를 휴대폰에서 직접 다운로드"
            textSize = 14f
            setTextColor(Color.DKGRAY)
        })
        urlInput = EditText(this).apply {
            hint = "https://... 영상 주소를 붙여넣으세요"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        add(urlInput)
        add(Button(this).apply { text = "클립보드에서 붙여넣기"; setOnClickListener { paste() } })
        add(TextView(this).apply { text = "파일 형식 / 화질"; textSize = 16f })
        options = Spinner(this)
        options.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("최고 화질 영상", "1080p 이하", "720p 이하", "480p 이하", "MP3 오디오")
        )
        add(options)
        start = Button(this).apply { text = "다운로드 시작"; setOnClickListener { download() } }
        add(start)
        cancel = Button(this).apply {
            text = "다운로드 취소"
            setOnClickListener {
                startService(Intent(this@MainActivity, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL))
            }
        }
        add(cancel)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        add(progress)
        status = TextView(this).apply { text = "다운로드할 링크를 입력하세요."; textSize = 14f }
        add(status)
        open = Button(this).apply { text = "마지막 저장 파일 열기"; setOnClickListener { openLast() } }
        add(open)
        receive(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }
    override fun onResume() { super.onResume(); handler.post(updater) }
    override fun onPause() { handler.removeCallbacks(updater); super.onPause() }
    private fun receive(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val link = extract(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
            if (link != null) urlInput.setText(link)
        }
    }
    private fun paste() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val link = extract(text)
        if (link != null) urlInput.setText(link)
        else Toast.makeText(this, "클립보드에 유효한 링크가 없어요.", Toast.LENGTH_SHORT).show()
    }
    private fun extract(text: String): String? {
        val value = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE).find(text)?.value
            ?.trimEnd('.', ',', ')', ']', '}', '。') ?: return null
        val uri = Uri.parse(value)
        return if ((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()) value else null
    }
    private fun download() {
        if (DownloadState.running) return
        val url = extract(urlInput.text.toString())
        if (url == null) { urlInput.error = "올바른 http(s) URL을 입력하세요"; return }
        DownloadState.running = true
        DownloadState.cancelRequested = false
        DownloadState.percent = 0
        DownloadState.message = "작업 준비 중"
        try {
            startForegroundService(Intent(this, DownloadService::class.java).apply {
                action = DownloadService.ACTION_START
                putExtra(DownloadService.EXTRA_URL, url)
                putExtra(DownloadService.EXTRA_FORMAT, options.selectedItemPosition)
            })
        } catch (e: Exception) {
            DownloadState.running = false
            DownloadState.message = "실행 실패: " + e.localizedMessage
        }
        refresh()
    }
    private fun refresh() {
        status.text = DownloadState.message
        progress.progress = DownloadState.percent.coerceIn(0,100)
        progress.isIndeterminate = DownloadState.running && DownloadState.percent == 0
        start.isEnabled = !DownloadState.running
        cancel.visibility = if (DownloadState.running) View.VISIBLE else View.GONE
        open.visibility = if (getSharedPreferences("last",MODE_PRIVATE).contains("uri")) View.VISIBLE else View.GONE
    }
    private fun openLast() {
        val p = getSharedPreferences("last",MODE_PRIVATE)
        val uri = p.getString("uri", null) ?: return
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uri), p.getString("mime","video/mp4"))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "파일 열기"))
        } catch (e: Exception) {
            Toast.makeText(this,"재생할 앱을 찾지 못했어요.",Toast.LENGTH_SHORT).show()
        }
    }
}
