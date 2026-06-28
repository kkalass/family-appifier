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
        val url = request.url
        val host = url.host ?: ""
        val scheme = url.scheme ?: ""

        if (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)) {
            if (isWhitelisted(host)) {
                return false // Let WebView load the page
            } else {
                // Block navigation and show a Toast warning
                Toast.makeText(context, "Access restricted: ${host}", Toast.LENGTH_LONG).show()
                return true // Stop navigation
            }
        } else {
            // Handle non-web schemes (tel:, mailto:, intent:, etc.)
            try {
                val intent = Intent(Intent.ACTION_VIEW, url)
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "No app available to handle this request", Toast.LENGTH_SHORT).show()
            }
            return true // Handled by sending to external system handler
        }
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
