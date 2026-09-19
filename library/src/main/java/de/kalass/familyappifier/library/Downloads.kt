package de.kalass.familyappifier.library

import android.Manifest
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Saves the files a page downloads to the device's Downloads folder.
 *
 * Android's DownloadManager can't be used for this: it downloads in a system process
 * that ignores this app's network security config and therefore rejects the private
 * CAs of self-hosted servers, and it can't reach blob: URLs, which only exist inside
 * the page. So the app fetches web URLs itself, and the page hands over the content
 * of blob: and data: URLs through a JavaScript bridge.
 */
class Downloads(
    private val activity: AppCompatActivity,
    private val webView: WebView
) : DownloadListener {

    private val executor = Executors.newCachedThreadPool()
    private val transfers = ConcurrentHashMap<String, Transfer>()

    private val waitingForPermission = mutableListOf<() -> Unit>()
    private val storagePermission = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val waiting = waitingForPermission.toList()
        waitingForPermission.clear()
        if (granted) {
            waiting.forEach { it() }
        } else {
            toast("Download failed: no permission to save files")
        }
    }

    init {
        webView.addJavascriptInterface(Bridge(), BRIDGE)
    }

    override fun onDownloadStart(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long
    ) {
        withStoragePermission {
            // Only the page knows the file name its download link suggests
            webView.evaluateJavascript("$PAGE_SCRIPT;$HELPER.suggestedName(${JSONObject.quote(url)})") { result ->
                val suggestedName = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                when (Uri.parse(url).scheme?.lowercase()) {
                    "http", "https" -> fetch(url, userAgent, contentDisposition, mimetype, suggestedName)
                    "blob", "data" -> fetchFromPage(url, contentDisposition, mimetype, suggestedName)
                    else -> toast("Download failed: unsupported address")
                }
            }
        }
    }

    /** Saves a URL as if the page had offered it for download */
    fun save(url: String) {
        onDownloadStart(url, webView.settings.userAgentString, null, null, -1)
    }

    /** Before Android 10, writing to the public Downloads folder needs a permission */
    private fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        waitingForPermission += action
        if (waitingForPermission.size == 1) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** Downloads within this app, so that its network security config applies */
    private fun fetch(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        suggestedName: String?
    ) {
        toast("Download started...")
        executor.execute {
            var target: Target? = null
            try {
                val connection = connect(url, userAgent)
                try {
                    val type = (connection.contentType ?: mimeType)?.substringBefore(';')?.trim()
                    val name = (connection.getHeaderField("Content-Disposition") ?: contentDisposition)
                        ?.let(::fileNameFrom)
                        ?: suggestedName
                        ?: URLUtil.guessFileName(url, null, type)
                    val file = createTarget(name, type).also { target = it }
                    connection.inputStream.use { it.copyTo(file.output) }
                    saved(file)
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                target?.discard()
                failed(e)
            }
        }
    }

    private fun connect(url: String, userAgent: String): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            if (current.protocol != "http" && current.protocol != "https") {
                throw IOException("Unsupported address $current")
            }
            val connection = current.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            // Redirects are followed by hand, so that each host only gets its own cookies
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(current.toString())?.let {
                connection.setRequestProperty("Cookie", it)
            }
            val status = connection.responseCode
            when (status) {
                in 200..299 -> return connection
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    current = URL(current, location ?: throw IOException("HTTP $status without Location"))
                }
                else -> {
                    connection.disconnect()
                    throw IOException("HTTP $status")
                }
            }
        }
        throw IOException("Too many redirects")
    }

    /** blob: and data: URLs only exist within the page, so the page sends their content over */
    private fun fetchFromPage(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        suggestedName: String?
    ) {
        val id = UUID.randomUUID().toString()
        transfers[id] = Transfer(suggestedName ?: contentDisposition?.let(::fileNameFrom), mimeType)
        toast("Download started...")
        webView.evaluateJavascript("$HELPER.send(${JSONObject.quote(url)}, ${JSONObject.quote(id)})", null)
    }

    private inner class Transfer(val name: String?, val mimeType: String?) {
        var target: Target? = null

        /** Only created once the page delivers the content, and with it the actual type */
        fun target(pageType: String): Target = target ?: run {
            val type = pageType.ifEmpty { null } ?: mimeType
            // The URL of page content is meaningless, unlike that of a file on a server
            val extension = type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            createTarget(name ?: "download" + (extension?.let { ".$it" } ?: ""), type)
        }.also { target = it }
    }

    /** Receives the content of blob: and data: URLs from [PAGE_SCRIPT], on a background thread */
    private inner class Bridge {
        @JavascriptInterface
        fun write(id: String, base64: String, type: String): Boolean {
            val transfer = transfers[id] ?: return false
            return try {
                transfer.target(type).output.write(Base64.decode(base64, Base64.DEFAULT))
                true
            } catch (e: Exception) {
                fail(id, e)
                false
            }
        }

        @JavascriptInterface
        fun end(id: String, type: String) {
            val transfer = transfers[id] ?: return
            try {
                saved(transfer.target(type))
                transfers.remove(id)
            } catch (e: Exception) {
                fail(id, e)
            }
        }

        @JavascriptInterface
        fun fail(id: String, message: String) {
            fail(id, IOException(message))
        }

        private fun fail(id: String, error: Exception) {
            val transfer = transfers.remove(id) ?: return
            transfer.target?.discard()
            failed(error)
        }
    }

    private fun createTarget(name: String, mimeType: String?): Target {
        val fileName = safeFileName(name)
        val type = mimeTypeFor(fileName, mimeType)
        val context = activity.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStoreTarget(context, fileName, type)
        } else {
            LegacyTarget(context, fileName, type)
        }
    }

    private fun saved(target: Target) {
        val uri = target.complete()
        activity.runOnUiThread {
            val message = "Saved ${target.name} to Downloads"
            if (activity.isDestroyed) {
                toast(message)
                return@runOnUiThread
            }
            val snackbar = Snackbar.make(webView, message, Snackbar.LENGTH_LONG)
            if (uri != null) {
                snackbar.setAction("Open") { open(uri, target.mimeType) }
            }
            snackbar.show()
        }
    }

    /** Hands the file to whichever app is registered for its type */
    private fun open(uri: Uri, mimeType: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            toast("No app available to open this file")
        }
    }

    private fun failed(error: Exception) {
        activity.runOnUiThread { toast("Download failed: ${error.message}") }
    }

    private fun toast(message: String) {
        Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val BRIDGE = "FamilyAppifierDownloads"
        private const val HELPER = "window.__familyAppifier"
        private const val MAX_REDIRECTS = 10

        /**
         * Prepares a page for handing over its downloads. It needs to run before a
         * download is triggered, and may run any number of times.
         */
        const val PAGE_SCRIPT = """
(function () {
  if (${HELPER}) return;
  var CHUNK = 1024 * 1024;
  var names = Object.create(null);
  var blobs = Object.create(null);

  // The app only learns the URL of a download, so remember the file name its link suggests
  function remember(link) {
    if (link && link.href && link.hasAttribute('download')) {
      names[link.href] = link.getAttribute('download');
    }
  }
  document.addEventListener('click', function (event) {
    remember(event.target.closest && event.target.closest('a[download]'));
  }, true);
  // Links that aren't part of the document never reach the listener above
  var click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    remember(this);
    return click.apply(this, arguments);
  };
  var dispatchEvent = HTMLAnchorElement.prototype.dispatchEvent;
  HTMLAnchorElement.prototype.dispatchEvent = function (event) {
    if (event && event.type === 'click') remember(this);
    return dispatchEvent.apply(this, arguments);
  };

  // Pages usually revoke a blob URL right after clicking its download link, before the
  // download has reached the app. So keep hold of the blob itself for a while.
  var createObjectURL = URL.createObjectURL;
  URL.createObjectURL = function (object) {
    var url = createObjectURL.apply(URL, arguments);
    if (object instanceof Blob) blobs[url] = object;
    return url;
  };
  var revokeObjectURL = URL.revokeObjectURL;
  URL.revokeObjectURL = function (url) {
    revokeObjectURL.apply(URL, arguments);
    setTimeout(function () { delete blobs[url]; }, url in names ? 60000 : 0);
  };

  ${HELPER} = {
    suggestedName: function (url) {
      return names[url] || null;
    },
    send: function (url, id) {
      var bridge = window.${BRIDGE};
      var blob = blobs[url];
      (blob ? Promise.resolve(blob) : fetch(url).then(function (response) { return response.blob(); }))
        .then(function (blob) {
          var offset = 0;
          (function next() {
            if (offset >= blob.size) return bridge.end(id, blob.type);
            var reader = new FileReader();
            reader.onload = function () {
              offset += CHUNK;
              if (bridge.write(id, reader.result.substring(reader.result.indexOf(',') + 1), blob.type)) next();
            };
            reader.onerror = function () { bridge.fail(id, String(reader.error)); };
            reader.readAsDataURL(blob.slice(offset, offset + CHUNK));
          })();
        })
        .catch(function (error) { bridge.fail(id, String(error)); });
    }
  };
})();
"""
    }
}

/** A file in the Downloads folder that is still being written */
private interface Target {
    val name: String
    val mimeType: String
    val output: OutputStream

    /** Makes the file visible and returns an address other apps may open it with */
    fun complete(): Uri?

    fun discard()
}

@RequiresApi(Build.VERSION_CODES.Q)
private class MediaStoreTarget(
    context: Context,
    override val name: String,
    override val mimeType: String
) : Target {
    private val resolver = context.contentResolver
    private val uri = resolver.insert(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            // Keeps half-written files hidden from other apps
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
    ) ?: throw IOException("Cannot create $name in Downloads")

    override val output: OutputStream =
        resolver.openOutputStream(uri) ?: throw IOException("Cannot write $name")

    override fun complete(): Uri {
        output.close()
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri
    }

    override fun discard() {
        runCatching { output.close() }
        resolver.delete(uri, null, null)
    }
}

/** Before Android 10 there is no MediaStore entry for downloads, only the folder itself */
@Suppress("DEPRECATION")
private class LegacyTarget(
    private val context: Context,
    name: String,
    override val mimeType: String
) : Target {
    private val file = uniqueFile(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        name
    )
    override val name: String = file.name
    override val output: OutputStream = FileOutputStream(file)

    override fun complete(): Uri? {
        output.close()
        // Lists the file among the downloads, which also provides an address to open it with
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = downloadManager.addCompletedDownload(
            name, name, true, mimeType, file.absolutePath, file.length(), true
        )
        return downloadManager.getUriForDownloadedFile(id)
    }

    override fun discard() {
        runCatching { output.close() }
        file.delete()
    }

    private fun uniqueFile(directory: File, name: String): File {
        directory.mkdirs()
        val base = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var file = File(directory, name)
        var counter = 1
        while (file.exists()) {
            file = File(directory, "$base (${counter++})$extension")
        }
        return file
    }
}

private val UNSAFE_FILE_NAME_CHARACTERS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

private fun safeFileName(name: String): String =
    name.replace(UNSAFE_FILE_NAME_CHARACTERS, "_").trim().trimStart('.').ifEmpty { "download" }

/**
 * MediaStore appends another extension when the MIME type doesn't match the file name,
 * and servers often just send application/octet-stream - so the extension wins.
 */
private fun mimeTypeFor(fileName: String, mimeType: String?): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
        ?: mimeType?.substringBefore(';')?.trim()?.lowercase()?.ifEmpty { null }
        ?: "application/octet-stream"

/**
 * The file name of a Content-Disposition header. Unlike URLUtil.guessFileName, this also
 * understands "inline" and the RFC 6266 filename* parameter, which servers use for
 * non-ASCII names.
 */
internal fun fileNameFrom(contentDisposition: String): String? {
    Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;\s]+)""", RegexOption.IGNORE_CASE)
        .find(contentDisposition)?.let { match ->
            val charset = match.groupValues[1].ifBlank { "UTF-8" }
            // Percent-encoding, where "+" is just a plus and not a space
            runCatching { URLDecoder.decode(match.groupValues[2].replace("+", "%2B"), charset) }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
    Regex("""filename\s*=\s*(?:"((?:\\.|[^"\\])*)"|([^;]+))""", RegexOption.IGNORE_CASE)
        .find(contentDisposition)?.let { match ->
            val quoted = match.groupValues[1].replace(Regex("""\\(.)"""), "\$1")
            return quoted.ifEmpty { match.groupValues[2].trim() }.takeIf { it.isNotBlank() }
        }
    return null
}
