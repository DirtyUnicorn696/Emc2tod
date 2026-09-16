package dev.codecompiler.android

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

private const val TAG = "CodeCompiler"

private val BG = Color.parseColor("#0b0e13")
private val FG = Color.parseColor("#dbe4ee")
private val DIM = Color.parseColor("#7d8b9c")
private val ACCENT = Color.parseColor("#4d8bff")

class MainActivity : ComponentActivity() {

    companion object {
        // ================================================================
        //  АДРЕС ГЕНЕРАТОРА. Если генератор будет переименован —
        //  поменяйте строку на новый адрес "https://perchance.org/имя".
        // ================================================================
        private const val START_URL = "https://perchance.org/compapk"

        // Метка в User-Agent: по ней веб-часть понимает, что она открыта в приложении.
        private const val UA_SUFFIX = " CodeCompilerAndroid/1.0"
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Системный выбор файла для панели «Импорт кода»: <input type="file"> внутри
        // WebView сам ничего не открывает, поэтому запрос пробрасываем в Android.
        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null
            if (callback == null) return@registerForActivityResult
            val data = result.data
            val picked = ArrayList<Uri>()
            if (data != null) {
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) picked.add(clip.getItemAt(i).uri)
                } else if (data.data != null) {
                    picked.add(data.data!!)
                }
            }
            callback.onReceiveValue(if (result.resultCode == RESULT_OK && picked.isNotEmpty()) picked.toTypedArray() else null)
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(BG)

        webView = WebView(this)
        webView.setBackgroundColor(BG)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.visibility = View.GONE

        errorView = buildErrorView()
        errorView.visibility = View.GONE

        root.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(progress, FrameLayout.LayoutParams(MATCH_PARENT, dp(3), Gravity.TOP))
        root.addView(errorView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        configureWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        // Если системный выбор файла не успел вернуть результат — освобождаем колбэк,
        // иначе WebView останется ждать ответа вечно.
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    //  Настройка WebView
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val wv = webView
        val s = wv.settings

        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadsImagesAutomatically = true
        s.cacheMode = WebSettings.LOAD_DEFAULT

        // Зум и системный масштаб шрифта выключены: иначе вёрстка IDE разъезжается.
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.textZoom = 100

        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        // Доступ к содержимому нужен, чтобы WebView мог прочитать файл, выбранный
        // в системном диалоге (<input type="file"> в панели «Импорт кода»).
        // Файлы по file:// по-прежнему запрещены (allowFileAccess = false).
        s.allowContentAccess = true
        s.setGeolocationEnabled(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.javaScriptCanOpenWindowsAutomatically = false
        s.userAgentString = s.userAgentString + UA_SUFFIX

        // Страница уже тёмная, так что принудительная инверсия Chrome только испортит цвета.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            s.forceDark = WebSettings.FORCE_DARK_OFF
        }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(wv, true)

        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isLongClickable = false
        wv.setOnLongClickListener { true }

        // Мост для кнопок веб-части: «Скачать код», сохранение готового APK
        // и открытие внешних ссылок (src/app.js, src/githubpanel.js).
        wv.addJavascriptInterface(Bridge(), "CodeCompilerHost")

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d(TAG, "[js] " + msg.message() + "  (" + msg.sourceId() + ":" + msg.lineNumber() + ")")
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                return try {
                    val intent = params.createIntent()
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    if (intent.resolveActivity(packageManager) == null) throw ActivityNotFoundException()
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "file chooser failed", e)
                    fileChooserCallback = null
                    toast("Не нашлось приложения для выбора файла")
                    false
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme ?: return false
                if (scheme != "http" && scheme != "https") return true
                val host = url.host ?: return false
                if (host == "perchance.org" || host.endsWith(".perchance.org")) return false
                return openExternally(url)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress.progress = 0
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                errorView.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    showError("Код ошибки " + error.errorCode + ": " + error.description)
                }
            }
        }
    }

    private fun openExternally(url: Uri): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, url))
            true
        } catch (e: ActivityNotFoundException) {
            toast("Нет приложения, которое откроет эту ссылку")
            true
        }
    }

    // ------------------------------------------------------------------
    //  Экран ошибки загрузки
    // ------------------------------------------------------------------

    private fun buildErrorView(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setBackgroundColor(BG)
        box.setPadding(dp(28), dp(28), dp(28), dp(28))

        val title = TextView(this)
        title.text = "Нет соединения"
        title.setTextColor(FG)
        title.textSize = 19f
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER

        errorText = TextView(this)
        errorText.setTextColor(DIM)
        errorText.textSize = 13f
        errorText.gravity = Gravity.CENTER
        errorText.setPadding(0, dp(10), 0, dp(22))

        val retry = Button(this)
        retry.text = "Повторить"
        retry.setTextColor(Color.WHITE)
        retry.textSize = 15f
        retry.isAllCaps = false
        retry.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(ACCENT)
        }
        retry.setPadding(dp(30), dp(12), dp(30), dp(12))
        retry.setOnClickListener {
            errorView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.reload()
        }

        box.addView(title)
        box.addView(errorText)
        box.addView(retry)
        return box
    }

    private fun showError(message: String) {
        errorText.text = "Не удалось загрузить страницу.\n" +
            message +
            "\n\nПроверьте подключение к интернету и нажмите «Повторить»."
        errorView.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE
        progress.visibility = View.GONE
    }

    // ------------------------------------------------------------------
    //  Мост в веб-часть: сохранение файлов и внешние ссылки
    // ------------------------------------------------------------------

    inner class Bridge {

        /** Кнопка «Скачать код» (src/app.js, downloadCode). */
        @JavascriptInterface
        fun saveCode(name: String, text: String) {
            val safe = sanitize(name)
            runOnUiThread {
                saveToDownloads(safe, "text/plain", text.toByteArray(Charsets.UTF_8))
            }
        }

        /**
         * Панель «GitHub · сборка APK» присылает сюда готовый APK (или ZIP)
         * в base64 — так бинарные файлы не проходят через текст и не портятся.
         */
        @JavascriptInterface
        fun saveBase64(name: String, base64Data: String, mime: String) {
            val safe = sanitize(name)
            val type = if (mime.isBlank()) "application/octet-stream" else mime
            val bytes = try {
                Base64.decode(base64Data, Base64.DEFAULT)
            } catch (e: Exception) {
                Log.e(TAG, "base64 decode failed", e)
                null
            }
            if (bytes == null) {
                runOnUiThread { toast("Не удалось декодировать файл") }
                return
            }
            runOnUiThread { saveToDownloads(safe, type, bytes) }
        }

        /** Открыть ссылку в системном браузере (кнопки «Открыть репозиторий» и т. п.). */
        @JavascriptInterface
        fun openUrl(url: String) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return
            runOnUiThread { openExternally(Uri.parse(url)) }
        }

        @JavascriptInterface
        fun toast(message: String) {
            runOnUiThread { this@MainActivity.toast(message) }
        }
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        return if (cleaned.isEmpty()) "code.txt" else cleaned
    }

    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pending = ContentValues()
                pending.put(MediaStore.Downloads.DISPLAY_NAME, name)
                pending.put(MediaStore.Downloads.MIME_TYPE, mime)
                pending.put(MediaStore.Downloads.IS_PENDING, 1)

                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)
                if (uri == null) {
                    toast("Не удалось создать файл")
                    return
                }
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }

                val done = ContentValues()
                done.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, done, null, null)
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                if (!dir.exists()) dir.mkdirs()
                File(dir, name).writeBytes(bytes)
            }
            toast("Сохранено в «Загрузки»: " + name)
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            toast("Ошибка сохранения: " + (e.message ?: "неизвестно"))
        }
    }

    // ------------------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
