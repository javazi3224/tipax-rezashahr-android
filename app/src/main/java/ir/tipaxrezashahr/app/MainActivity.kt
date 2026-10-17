package ir.tipaxrezashahr.app

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * اپ اندروید ساده‌ی تیپاکس شعبه رضاشهر - یه پوسته (WebView) دور همین سایتیه
 * که همین الان رو مرورگر کار می‌کنه (tipaxrezashahr.ir)، بدون بک‌اند یا
 * منطق جدا؛ هر تغییری رو سایت وب بدی همینجا هم خودش به‌روز می‌شه، نیازی به
 * آپدیت اپ نیست. امکاناتی که یه اپ واقعی نیاز داره و خودِ WebView پیش‌فرض
 * نداره رو اضافه کرده: آپلود فایل/عکس (برای آپلود اکسل کدهای رهگیری یا عکس
 * فیش)، دانلود فایل (اکسل/PDF گزارش‌ها) با همون کوکی ورود، ناوبری با دکمه
 * برگشت گوشی، pull-to-refresh، و یه صفحه‌ی «قطع اینترنت» با دکمه‌ی تلاش
 * دوباره.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // سایت هنوز HTTPS نداره (نگاه کن به network_security_config.xml) -
        // اگه بعداً گواهی SSL گرفت، این خط رو به https:// تغییر بده.
        private const val BASE_URL = "http://tipaxrezashahr.ir/"
        private const val HOST = "tipaxrezashahr.ir"
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineLayout: LinearLayout

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // --- انتخاب فایل (برای <input type="file"> تو فرم‌های سایت، مثل
    // آپلود اکسل کدهای رهگیری یا عکس فیش) ---
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }
        val uris: Array<Uri> = when {
            data.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
            }
            data.data != null -> arrayOf(data.data!!)
            else -> arrayOf()
        }
        callback.onReceiveValue(uris)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* نتیجه مهم نیست - اگه رد بشه، فقط گزینه‌ی دوربین تو چوزر نمی‌آد */ }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "برای دانلود فایل، دسترسی ذخیره‌سازی لازمه.", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        offlineLayout = findViewById(R.id.offlineLayout)
        val retryButton = findViewById<Button>(R.id.retryButton)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // آپلود فایل محلی از WebView نیاز به این دو تا داره
            allowFileAccess = true
            allowContentAccess = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                // لینک‌های همین سایت همینجا تو اپ باز بشن؛ هر چیز دیگه‌ای
                // (مثلاً لینک بله یا شماره تلفن) با اپ خارجی مناسبش.
                if (url.host != null && url.host!!.contains(HOST)) {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (e: Exception) {
                    false
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                // فقط خطای مربوط به ناوبری اصلی صفحه (نه یه ساب‌ریسورس مثل
                // فونت/آیکون) رو به‌عنوان «آفلاین» نشون بده.
                if (request.isForMainFrame) {
                    showOffline()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }

                val intent = params.createIntent()
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        // دانلود فایل (اکسل/PDF گزارش‌ها و کدهای رهگیری) - باید کوکی ورود
        // (session) رو هم به DownloadManager بدیم، وگرنه سرور لینک رو
        // به‌عنوان کاربر مهمان (بدون لاگین) می‌بینه و رد می‌کنه.
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            try {
                val cookie = CookieManager.getInstance().getCookie(url)
                val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    addRequestHeader("cookie", cookie)
                    setMimeType(mimeType)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    setTitle(fileName)
                    setAllowedOverMetered(true)
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "دانلود شروع شد: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "دانلود انجام نشد.", Toast.LENGTH_SHORT).show()
            }
        }

        swipeRefresh.setOnRefreshListener { webView.reload() }
        retryButton.setOnClickListener {
            offlineLayout.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.reload()
        }

        if (savedInstanceState == null) {
            webView.loadUrl(BASE_URL)
        }

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun showOffline() {
        webView.visibility = View.GONE
        offlineLayout.visibility = View.VISIBLE
        swipeRefresh.isRefreshing = false
        progressBar.visibility = View.GONE
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
