package de.kalass.familyappifier.library

import android.app.DownloadManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

open class WebViewActivity : AppCompatActivity() {

    protected lateinit var webView: WebView
    protected lateinit var progressBar: ProgressBar

    private var startUrl: String = ""
    private var whitelist: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        readMetaDataConfig()
        setupWebView()
        setupBackNavigation()

        if (savedInstanceState == null) {
            if (startUrl.isNotEmpty()) {
                webView.loadUrl(startUrl)
            } else {
                Toast.makeText(this, "Configuration Error: Start URL not specified", Toast.LENGTH_LONG).show()
            }
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun readMetaDataConfig() {
        try {
            // Try reading from activity metadata first
            val activityInfo = packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)
            var bundle = activityInfo.metaData

            // Fallback to application metadata if not found in activity
            if (bundle == null || !bundle.containsKey("de.kalass.familyappifier.START_URL")) {
                val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                bundle = appInfo.metaData
            }

            if (bundle != null) {
                startUrl = bundle.getString("de.kalass.familyappifier.START_URL") ?: ""
                val whitelistStr = bundle.getString("de.kalass.familyappifier.DOMAIN_WHITELIST") ?: ""
                if (whitelistStr.isNotEmpty()) {
                    whitelist = whitelistStr.split(",").map { it.trim() }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        
        // Cache settings - LOAD_DEFAULT uses web cache-control headers automatically
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Zoom support
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // Viewport and layout
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // Cookie management
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Clients
        webView.webViewClient = WebViewClientImpl(this, whitelist)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress == 100) {
                    progressBar.visibility = View.GONE
                } else {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                }
            }
        }

        // Downloads integration
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            try {
                val uri = Uri.parse(url)
                val request = DownloadManager.Request(uri).apply {
                    setMimeType(mimetype)
                    val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    setTitle(filename)
                    setDescription("Downloading file...")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)

                    // Forward current session cookies to the download request
                    val cookies = CookieManager.getInstance().getCookie(url)
                    if (cookies != null) {
                        addRequestHeader("Cookie", cookies)
                    }
                    addRequestHeader("User-Agent", userAgent)
                }

                val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.enqueue(request)
                Toast.makeText(this, "Download started...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // Disable callback to call default system action
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        // Ensure cookies are flushed to persistent storage
        CookieManager.getInstance().flush()
    }
}
