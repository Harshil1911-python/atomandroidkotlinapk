package com.atombills.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (!results.values.all { it }) {
            Toast.makeText(this, "Some permissions denied. Features may be limited.", Toast.LENGTH_LONG).show()
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        val uris: Array<Uri>? = when {
            result.resultCode != Activity.RESULT_OK -> null
            result.data?.clipData != null -> {
                val clip = result.data!!.clipData!!
                Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
            }
            result.data?.data != null -> arrayOf(result.data!!.data!!)
            cameraPhotoUri != null -> arrayOf(cameraPhotoUri!!)
            else -> null
        }
        callback.onReceiveValue(uris)
        cameraPhotoUri = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        webView.contentDescription = getString(R.string.webview_content_description)
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupWebView()
        requestNeededPermissions()

        // Load via rewritten HTML so ?v=20 is stripped and $$ exists before any page script
        loadRewrittenHtml("index.html")

        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** Read HTML from assets, strip cache-busting query strings, inject $/$ $ polyfill first. */
    private fun loadRewrittenHtml(assetName: String) {
        try {
            val raw = assets.open(assetName).bufferedReader().use { it.readText() }
            val html = rewriteHtml(raw)
            webView.loadDataWithBaseURL(
                "file:///android_asset/",
                html,
                "text/html",
                "UTF-8",
                "file:///android_asset/$assetName"
            )
        } catch (e: Exception) {
            android.util.Log.e("AtomBills", "loadRewrittenHtml failed: ${e.message}")
            webView.loadUrl("file:///android_asset/$assetName")
        }
    }

    private fun rewriteHtml(raw: String): String {
        // Strip ?v=20 (and any ?v=digits) from script/link/href/src so file:// assets resolve
        var html = raw.replace(Regex("""(\.(?:js|css|png|jpg|jpeg|svg|mp3|webmanifest|html))\?v=\d+"""), "$1")
        // Also strip bare ?v= on sw.js etc.
        html = html.replace(Regex("""\?v=\d+"""), "")
        // Inject polyfill as the very first thing in <head> so inline scripts never see undefined $$
        val polyfill = """<script>window.\$=window.\$||function(s){return document.querySelector(s)};window.\$\$=window.\$\$||function(s){return document.querySelectorAll(s)};window.isAndroidApp=true;window.AndroidBridgeReady=true;</script>"""
        html = if (html.contains("<head>", ignoreCase = true)) {
            html.replaceFirst(Regex("(?i)<head>"), "<head>$polyfill")
        } else {
            polyfill + html
        }
        return html
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.textZoom = 100
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = false
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleDownload(url, contentDisposition, mimeType)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                view?.evaluateJavascript(
                    """
                    (function(){
                      if (typeof window.\$ !== 'function') {
                        window.\$ = function(s){ return document.querySelector(s); };
                      }
                      if (typeof window.\$\$ !== 'function') {
                        window.\$\$ = function(s){ return document.querySelectorAll(s); };
                      }
                      window.isAndroidApp = true;
                      window.AndroidBridgeReady = true;
                    })();
                    """.trimIndent(),
                    null
                )
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                view?.evaluateJavascript(
                    """
                    (function(){
                      window.isAndroidApp = true;
                      window.AndroidBridgeReady = true;
                      if (typeof window.\$ !== 'function') {
                        window.\$ = function(s){ return document.querySelector(s); };
                      }
                      if (typeof window.\$\$ !== 'function') {
                        window.\$\$ = function(s){ return document.querySelectorAll(s); };
                      }
                      if (window.AndroidBridge && !window.__atomSharePatched) {
                        window.__atomSharePatched = true;
                        var origShare = window.sharePngDataUrl;
                        window.sharePngDataUrl = async function(dataUrl, filename) {
                          try {
                            if (window.AndroidBridge && dataUrl) {
                              AndroidBridge.saveBase64AndShare(dataUrl, filename || 'invoice.png', 'image/png');
                              return true;
                            }
                          } catch (e) {}
                          if (typeof origShare === 'function') return origShare(dataUrl, filename);
                          return false;
                        };
                      }
                    })();
                    """.trimIndent(),
                    null
                )
            }

            /**
             * Serve every android_asset request ourselves:
             * - HTML: rewrite (strip ?v=, inject polyfill)
             * - JS/CSS/etc with ?v=: open clean path
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val uri = request?.url ?: return super.shouldInterceptRequest(view, request)
                if (uri.scheme != "file") return super.shouldInterceptRequest(view, request)
                val path = uri.path ?: return super.shouldInterceptRequest(view, request)
                if (!path.contains("/android_asset/")) return super.shouldInterceptRequest(view, request)

                val assetPath = path.substringAfter("/android_asset/")
                    .substringBefore("?")
                    .substringBefore("#")
                if (assetPath.isBlank()) return super.shouldInterceptRequest(view, request)

                return try {
                    if (assetPath.endsWith(".html", true) || assetPath.endsWith(".htm", true)) {
                        val raw = assets.open(assetPath).bufferedReader().use { it.readText() }
                        val rewritten = rewriteHtml(raw)
                        val bytes = rewritten.toByteArray(Charsets.UTF_8)
                        WebResourceResponse(
                            "text/html",
                            "UTF-8",
                            ByteArrayInputStream(bytes)
                        )
                    } else {
                        val mime = guessMime(assetPath)
                        val stream = assets.open(assetPath)
                        WebResourceResponse(mime, "UTF-8", stream)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AtomBills", "Asset intercept failed for $uri ($assetPath): ${e.message}")
                    super.shouldInterceptRequest(view, request)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                if (url == null || !url.startsWith("file://")) {
                    return super.shouldInterceptRequest(view, url)
                }
                val path = Uri.parse(url).path ?: return super.shouldInterceptRequest(view, url)
                if (!path.contains("/android_asset/")) return super.shouldInterceptRequest(view, url)
                val assetPath = path.substringAfter("/android_asset/")
                    .substringBefore("?")
                    .substringBefore("#")
                return try {
                    if (assetPath.endsWith(".html", true)) {
                        val raw = assets.open(assetPath).bufferedReader().use { it.readText() }
                        val rewritten = rewriteHtml(raw)
                        WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(rewritten.toByteArray(Charsets.UTF_8)))
                    } else {
                        WebResourceResponse(guessMime(assetPath), "UTF-8", assets.open(assetPath))
                    }
                } catch (e: Exception) {
                    super.shouldInterceptRequest(view, url)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return when {
                    url.startsWith("tel:") || url.startsWith("mailto:") ||
                    url.startsWith("whatsapp:") || url.startsWith("sms:") -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, "Cannot open link", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    // In-app HTML navigation: always serve rewritten page
                    url.startsWith("file:///android_asset/") &&
                        (url.contains(".html") || url.endsWith("android_asset/") || url.endsWith("android_asset")) -> {
                        val name = url.substringAfter("file:///android_asset/")
                            .substringBefore("?")
                            .substringBefore("#")
                            .ifBlank { "index.html" }
                        loadRewrittenHtml(name)
                        true
                    }
                    else -> false
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                cameraPhotoUri = null

                val acceptTypes = fileChooserParams?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
                val isImageOnly = acceptTypes.isNotEmpty() && acceptTypes.all { t ->
                    t.contains("image", ignoreCase = true) || t.startsWith("image/")
                }
                val wantsSpreadsheetOrText = acceptTypes.any {
                    it.contains("xlsx", true) || it.contains("xls", true) ||
                    it.contains("csv", true) || it.contains("txt", true) ||
                    it.contains("json", true) || it.contains("sheet", true) ||
                    it.contains("plain", true)
                }
                val isCapture = fileChooserParams?.isCaptureEnabled == true
                val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                val openIntent = if (wantsSpreadsheetOrText || (!isImageOnly && !isCapture)) {
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        if (acceptTypes.isNotEmpty()) {
                            putExtra(Intent.EXTRA_MIME_TYPES, expandMimeTypes(acceptTypes))
                        }
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                    }
                }

                val extraIntents = mutableListOf<Intent>()

                if ((isImageOnly || isCapture) && !wantsSpreadsheetOrText) {
                    val takePicture = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    if (takePicture.resolveActivity(packageManager) != null) {
                        createImageFile()?.let { file ->
                            cameraPhotoUri = FileProvider.getUriForFile(
                                this@MainActivity, "${packageName}.fileprovider", file
                            )
                            takePicture.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                            extraIntents.add(takePicture)
                        }
                    }
                }

                val chooserTitle = when {
                    wantsSpreadsheetOrText && acceptTypes.any {
                        it.contains("xlsx", true) || it.contains("xls", true) || it.contains("sheet", true)
                    } -> "Select Excel file (.xlsx)"
                    wantsSpreadsheetOrText -> "Select file"
                    isCapture || isImageOnly -> "Select photo"
                    else -> "Select file"
                }

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, openIntent)
                    putExtra(Intent.EXTRA_TITLE, chooserTitle)
                    if (extraIntents.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())
                    }
                }

                return try {
                    fileChooserLauncher.launch(chooser)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "Cannot open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                msg?.let {
                    android.util.Log.d("AtomBills", "${it.message()} -- ${it.sourceId()}:${it.lineNumber()}")
                }
                return true
            }
        }

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
    }

    private fun guessMime(path: String): String = when {
        path.endsWith(".js", true) -> "application/javascript"
        path.endsWith(".css", true) -> "text/css"
        path.endsWith(".html", true) || path.endsWith(".htm", true) -> "text/html"
        path.endsWith(".png", true) -> "image/png"
        path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
        path.endsWith(".svg", true) -> "image/svg+xml"
        path.endsWith(".json", true) || path.endsWith(".webmanifest", true) -> "application/json"
        path.endsWith(".mp3", true) -> "audio/mpeg"
        path.endsWith(".woff2", true) -> "font/woff2"
        path.endsWith(".woff", true) -> "font/woff"
        else -> "application/octet-stream"
    }

    private fun expandMimeTypes(accept: List<String>): Array<String> {
        val out = mutableSetOf<String>()
        for (a in accept) {
            when {
                a.contains("xlsx", true) || a.contains("sheet", true) -> {
                    out.add("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    out.add("application/vnd.ms-excel")
                    out.add("application/octet-stream")
                }
                a.contains("xls", true) -> {
                    out.add("application/vnd.ms-excel")
                    out.add("application/octet-stream")
                }
                a.contains("csv", true) -> {
                    out.add("text/csv")
                    out.add("text/comma-separated-values")
                    out.add("text/plain")
                }
                a.contains("txt", true) || a.contains("json", true) || a.contains("plain", true) -> {
                    out.add("text/plain")
                    out.add("application/json")
                }
                a.contains("image", true) || a.startsWith("image/") -> out.add("image/*")
                a.contains("/") -> out.add(a)
            }
        }
        if (out.isEmpty()) out.add("*/*")
        return out.toTypedArray()
    }

    private fun handleDownload(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            when {
                url.startsWith("data:") -> {
                    val comma = url.indexOf(',')
                    if (comma < 0) return
                    val header = url.substring(0, comma)
                    val data = url.substring(comma + 1)
                    val isBase64 = header.contains(";base64", ignoreCase = true)
                    val mime = header.substringAfter("data:").substringBefore(";").ifBlank {
                        mimeType ?: "application/octet-stream"
                    }
                    val bytes = if (isBase64) Base64.decode(data, Base64.DEFAULT) else data.toByteArray()
                    val ext = when {
                        mime.contains("png") -> "png"
                        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                        mime.contains("pdf") -> "pdf"
                        mime.contains("sheet") || mime.contains("excel") -> "xlsx"
                        mime.contains("csv") -> "csv"
                        mime.contains("json") || mime.contains("text") -> "txt"
                        else -> "bin"
                    }
                    val name = "atom_${System.currentTimeMillis()}.$ext"
                    val path = saveBytes(bytes, name) ?: return
                    shareFile(path, mime)
                }
                url.startsWith("blob:") -> {
                    Toast.makeText(this, "Use Share button", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimeType)
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            URLUtil.guessFileName(url, contentDisposition, mimeType)
                        )
                        allowScanningByMediaScanner()
                    }
                    val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBytes(bytes: ByteArray, fileName: String): String? = try {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val file = File(dir, fileName)
        FileOutputStream(file).use { it.write(bytes) }
        file.absolutePath
    } catch (_: Exception) {
        null
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.CAMERA)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    private fun createImageFile(): File? = try {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        File.createTempFile("ATOM_${stamp}_", ".jpg", getExternalFilesDir(Environment.DIRECTORY_PICTURES))
    } catch (_: Exception) {
        null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    fun shareText(title: String, text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Share via"))
    }

    fun shareFile(filePath: String, mimeType: String) {
        try {
            val file = File(filePath)
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifBlank { "application/octet-stream" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(contentResolver, "shared", uri)
            }
            startActivity(Intent.createChooser(intent, "Share via"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveBase64File(base64Data: String, fileName: String): String? = try {
        val pure = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
        val bytes = Base64.decode(pure, Base64.DEFAULT)
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val file = File(dir, fileName)
        FileOutputStream(file).use { it.write(bytes) }
        file.absolutePath
    } catch (_: Exception) {
        null
    }

    fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
