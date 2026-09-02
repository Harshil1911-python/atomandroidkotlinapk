package com.atombills.app

import android.webkit.JavascriptInterface

class WebAppInterface(private val activity: MainActivity) {

    @JavascriptInterface
    fun isAndroid(): Boolean = true

    @JavascriptInterface
    fun getAppVersion(): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "1.0.0"
    } catch (_: Exception) { "1.0.0" }

    @JavascriptInterface
    fun showToast(message: String) {
        activity.showToast(message)
    }

    @JavascriptInterface
    fun shareText(title: String, text: String) {
        activity.runOnUiThread { activity.shareText(title, text) }
    }

    @JavascriptInterface
    fun shareFile(filePath: String, mimeType: String) {
        activity.runOnUiThread { activity.shareFile(filePath, mimeType) }
    }

    @JavascriptInterface
    fun saveBase64File(base64Data: String, fileName: String): String {
        return activity.saveBase64File(base64Data, fileName) ?: ""
    }

    @JavascriptInterface
    fun saveBase64AndShare(base64Data: String, fileName: String, mimeType: String) {
        activity.runOnUiThread {
            val path = activity.saveBase64File(base64Data, fileName)
            if (path != null) activity.shareFile(path, mimeType)
            else activity.showToast("Failed to save file")
        }
    }

    @JavascriptInterface
    fun openShareSheet(text: String) {
        activity.runOnUiThread { activity.shareText("Atom Bills", text) }
    }
}
