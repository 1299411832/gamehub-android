package space.devmini.gamehub

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Single-activity WebView shell for https://mibear.top/.
 *
 * Site content is served remotely, so content updates never require an app release.
 * The shell only knows how to: stay on-site, hand foreign links to the system,
 * hand custom schemes (quark://, intent://, ...) to the matching app, download,
 * and recover from load errors.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            callback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L) return
            maybeInstallApk(id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progress)
        errorView = findViewById(R.id.error_view)

        findViewById<Button>(R.id.retry).setOnClickListener {
            errorView.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            webView.reload()
        }

        configureWebView()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )

        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL)
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = ShellWebViewClient()
        webView.webChromeClient = ShellChromeClient()
        // WebView.setDownloadListener has no getter, so Kotlin exposes no property syntax here.
        webView.setDownloadListener(ShellDownloadListener())
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onStop() {
        try {
            unregisterReceiver(downloadCompleteReceiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
        super.onStop()
    }

    // NOTE: the pending file-chooser callback is deliberately NOT cleared in onPause(),
    // because opening the system picker pauses this activity and the result is only
    // delivered after onResume(). It is reset when a new chooser is requested.

    // ------------------------------------------------------------------ webview client

    private inner class ShellWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            return when (url.scheme?.lowercase()) {
                "http", "https" -> {
                    if (isSameSite(url.host)) {
                        false // keep browsing inside the shell
                    } else {
                        openExternally(url)
                        true
                    }
                }
                else -> {
                    // quark://, uc://, xunlei://, baiduyun://, intent://, ...
                    launchUri(url)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            errorView.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            progressBar.visibility = View.GONE
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                progressBar.visibility = View.GONE
                errorView.visibility = View.VISIBLE
            }
        }
    }

    // ------------------------------------------------------------------ chrome client

    private inner class ShellChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progressBar.progress = newProgress
            progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onShowFileChooser(
            view: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            this@MainActivity.filePathCallback?.onReceiveValue(null)
            this@MainActivity.filePathCallback = filePathCallback
            return try {
                fileChooserLauncher.launch(fileChooserParams.createIntent())
                true
            } catch (e: ActivityNotFoundException) {
                this@MainActivity.filePathCallback = null
                toast(getString(R.string.no_app_for_link))
                false
            }
        }
    }

    // ------------------------------------------------------------------ downloads

    private inner class ShellDownloadListener : DownloadListener {
        override fun onDownloadStart(
            url: String,
            userAgent: String,
            contentDisposition: String,
            mimeType: String,
            contentLength: Long
        ) {
            val scheme = Uri.parse(url).scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                launchUri(Uri.parse(url))
                return
            }
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .setTitle(fileName)
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setDestinationInExternalFilesDir(
                        applicationContext,
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName
                    )
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)

                if (userAgent.isNotBlank()) {
                    request.addRequestHeader("User-Agent", userAgent)
                }
                CookieManager.getInstance().getCookie(url)?.let { cookie ->
                    request.addRequestHeader("Cookie", cookie)
                }

                val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
                toast(getString(R.string.download_started))
            } catch (e: Exception) {
                toast(getString(R.string.download_failed))
            }
        }
    }

    private fun maybeInstallApk(downloadId: Long) {
        try {
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
            cursor?.use { c ->
                if (!c.moveToFirst()) return
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val mime = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE))
                val title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                val looksLikeApk = mime == APK_MIME_TYPE ||
                    title?.lowercase()?.endsWith(".apk") == true
                if (status == DownloadManager.STATUS_SUCCESSFUL && looksLikeApk) {
                    val uri = manager.getUriForDownloadedFile(downloadId)
                    if (uri != null) installApk(uri)
                }
            }
        } catch (e: Exception) {
            toast(getString(R.string.install_failed))
        }
    }

    private fun installApk(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.install_failed))
        }
    }

    // ------------------------------------------------------------------ link handling

    private fun isSameSite(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return h == BASE_HOST || h.endsWith(".$BASE_HOST")
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_app_for_link))
        } catch (e: Exception) {
            toast(getString(R.string.no_app_for_link))
        }
    }

    /** Hands any non-http scheme (including intent://) to the system without crashing. */
    private fun launchUri(uri: Uri) {
        try {
            if (uri.scheme?.lowercase() == "intent") {
                val parsed = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                try {
                    startActivity(parsed)
                } catch (e: ActivityNotFoundException) {
                    val fallback = parsed.getStringExtra("browser_fallback_url")
                    if (!fallback.isNullOrBlank()) {
                        openExternally(Uri.parse(fallback))
                    } else {
                        toast(getString(R.string.no_app_for_link))
                    }
                }
                return
            }
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_app_for_link))
        } catch (e: Exception) {
            toast(getString(R.string.no_app_for_link))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val HOME_URL = "https://mibear.top/"
        const val BASE_HOST = "mibear.top"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
