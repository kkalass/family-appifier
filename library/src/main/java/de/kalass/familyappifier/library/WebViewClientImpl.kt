package de.kalass.familyappifier.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class WebViewClientImpl(
    private val context: Context,
    private val whitelist: List<String>
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (isInternal(request.url)) {
            return false // Let WebView load the page
        }
        // Everything else leaves the app and becomes the system's business
        openExternally(context, request.url)
        return true
    }

    /**
     * Whether a URL belongs inside this app: a web URL on a whitelisted host.
     * Everything else - other hosts as well as tel:, mailto: or intent: links -
     * is handed to the system instead.
     */
    fun isInternal(url: Uri): Boolean {
        val scheme = url.scheme ?: return false
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
            return false
        }
        return isWhitelisted(url.host ?: "")
    }

    /**
     * Checks if a host name matches the whitelist.
     * Supports exact matches and wildcards (e.g. "*.home.kalass.de" matches "immich.home.kalass.de").
     */
    private fun isWhitelisted(host: String): Boolean {
        if (host.isEmpty()) return false
        for (pattern in whitelist) {
            val cleanPattern = pattern.trim()
            if (cleanPattern.startsWith("*.")) {
                val suffix = cleanPattern.substring(2)
                if (host.endsWith(suffix, ignoreCase = true) &&
                    (host.length == suffix.length || host[host.length - suffix.length - 1] == '.')) {
                    return true
                }
            } else {
                if (host.equals(cleanPattern, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }
}

/**
 * Hands a URL to the system so that whichever app is registered for it takes over -
 * the browser for web links, the dialer for tel:, and so on. Whether the link may
 * actually be opened is then decided by the usual device rules (Family Link, app
 * time limits, ...), not by this app.
 */
fun openExternally(context: Context, url: Uri) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, url).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val scheme = url.scheme ?: ""
            if (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)) {
                // Marks this as an untrusted link from web content, so that only
                // apps that opted into handling such links are offered
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No app available to handle this link", Toast.LENGTH_SHORT).show()
    }
}
