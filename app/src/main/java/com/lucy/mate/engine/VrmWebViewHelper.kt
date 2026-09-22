package com.lucy.mate.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * Shared helper for initializing and configuring WebViews that host the 3D VRM avatar.
 * Eliminates duplicate WebView configurations across Live2DView (in-app) and OverlayService (floating).
 */
object VrmWebViewHelper {
    private const val TAG = "VrmWebViewHelper"
    private const val VRM_INDEX_URL = "https://appassets.androidplatform.net/assets/avatar/index.html"

    @SuppressLint("SetJavaScriptEnabled")
    fun createConfiguredWebView(
        context: Context,
        jsBridge: Any? = null,
        onPageFinishedExtra: ((WebView) -> Unit)? = null
    ): WebView {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()

        return WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                mediaPlaybackRequiresUserGesture = false
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        Log.d(TAG, "[${it.messageLevel()}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                    }
                    return true
                }
            }

            if (jsBridge != null) {
                addJavascriptInterface(jsBridge, "AriaNative")
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url ?: return null

                    // 1. Direct handling of .vrm with full binary response, CORS headers, and fallback
                    if (url.path?.endsWith(".vrm", ignoreCase = true) == true) {
                        try {
                            var assetPath = url.path!!.trimStart('/').removePrefix("assets/")
                            // Seamless fallback: if soltini.vrm is requested, map it directly to lucy.vrm
                            if (assetPath.endsWith("soltini.vrm", ignoreCase = true)) {
                                assetPath = assetPath.replace("soltini.vrm", "lucy.vrm", ignoreCase = true)
                            }
                            val isStream = context.assets.open(assetPath)
                            val length = try {
                                context.assets.openFd(assetPath).length.toString()
                            } catch (_: Exception) {
                                null
                            }
                            val headers = mutableMapOf(
                                "Access-Control-Allow-Origin" to "*",
                                "Access-Control-Allow-Methods" to "GET, OPTIONS, HEAD",
                                "Access-Control-Allow-Headers" to "*",
                                "Content-Type" to "model/gltf-binary"
                            )
                            length?.let { headers["Content-Length"] = it }
                            return WebResourceResponse("model/gltf-binary", null, 200, "OK", headers, isStream)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed direct .vrm load: ${url.path}", e)
                        }
                    }

                    // 2. Delegate all other assets (html, js) through WebViewAssetLoader with CORS headers
                    val response = assetLoader.shouldInterceptRequest(url)
                    if (response != null) {
                        val headers = HashMap(response.responseHeaders ?: emptyMap())
                        headers["Access-Control-Allow-Origin"] = "*"
                        headers["Access-Control-Allow-Methods"] = "GET, OPTIONS, HEAD"
                        headers["Access-Control-Allow-Headers"] = "*"
                        response.responseHeaders = headers
                        if (url.path?.endsWith(".js", ignoreCase = true) == true) {
                            response.mimeType = "application/javascript"
                        }
                        return response
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Page finished loading: $url")
                    view?.evaluateJavascript(
                        "window.dispatchEvent(new Event('resize')); if (window.Aria) { window.Aria.setListening(true); }",
                        null
                    )
                    if (view != null) {
                        onPageFinishedExtra?.invoke(view)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    Log.e(TAG, "WebView error: ${error?.description} on ${request?.url}")
                }
            }

            loadUrl(VRM_INDEX_URL)
        }
    }
}
