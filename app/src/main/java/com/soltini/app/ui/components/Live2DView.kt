package com.soltini.app.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.soltini.app.engine.SoltiniGLRenderer
import com.soltini.app.engine.SoltiniGLTextureView
import com.soltini.app.engine.SoltiniLive2DEngine
import com.soltini.app.settings.AppSettings

/**
 * Live2DView
 *
 * Hosts either:
 * 1. The official native OpenGL Live2D Cubism model (Maya, Friday, Venom)
 * 2. The 3D VRM companion (Soltini)
 * Both render smoothly on top of a transparent canvas with real-time lip-sync.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun Live2DView(
    characterName: String,
    amplitude: Float,
    isSpeaking: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isVrm = characterName.contains("Lucy", ignoreCase = true) ||
            characterName.contains("Soltini", ignoreCase = true) ||
            characterName == AppSettings.CHARACTER_LUCY ||
            characterName == AppSettings.CHARACTER_SOLTINI

    if (isVrm) {
        val assetLoader = remember {
            WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
        }

        var webViewRef by remember { mutableStateOf<WebView?>(null) }
        var lastAmpUpdateTime by remember { mutableStateOf(0L) }
        var lastSentAmp by remember { mutableStateOf(-1f) }

        LaunchedEffect(isSpeaking, amplitude, webViewRef) {
            val wv = webViewRef ?: return@LaunchedEffect
            val clampedAmp = if (isSpeaking) (amplitude * 2.5f).coerceIn(0f, 1f) else 0f
            val now = System.currentTimeMillis()
            if (now - lastAmpUpdateTime >= 33L || kotlin.math.abs(clampedAmp - lastSentAmp) > 0.05f || !isSpeaking) {
                lastAmpUpdateTime = now
                lastSentAmp = clampedAmp
                val js = "if (window.Aria) { window.Aria.setTalking($isSpeaking); window.Aria.setMouthAmplitude($clampedAmp); }"
                wv.evaluateJavascript(js, null)
            }
        }

        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    createVrmWebView(ctx, assetLoader).also {
                        webViewRef = it
                    }
                },
                update = { wv ->
                    webViewRef = wv
                },
                onRelease = { wv ->
                    webViewRef = null
                    wv.destroy()
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        val engine = remember { SoltiniLive2DEngine(engineTag = "HomeView", initialCharacter = characterName) }
        var glViewRef by remember { mutableStateOf<SoltiniGLTextureView?>(null) }

        LaunchedEffect(characterName) {
            engine.requestCharacter(characterName, context)
        }

        LaunchedEffect(isSpeaking, glViewRef) {
            glViewRef?.setActiveFrameRate(isSpeaking)
        }

        LaunchedEffect(amplitude, isSpeaking) {
            val lipAmp = if (isSpeaking) (amplitude * 2.5f).coerceIn(0f, 1f) else 0f
            engine.setLipSync(lipAmp)
        }

        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    SoltiniGLTextureView(ctx).apply {
                        setRenderer(SoltiniGLRenderer(ctx, engine))

                        var lastX = 0f
                        var lastY = 0f
                        var downTime = 0L
                        setOnTouchListener { _, event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    lastX = event.x
                                    lastY = event.y
                                    downTime = System.currentTimeMillis()
                                    true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    val dx = (event.x - lastX) / width.toFloat()
                                    val dy = (lastY - event.y) / height.toFloat()
                                    engine.onDrag(dx, dy)
                                    lastX = event.x
                                    lastY = event.y
                                    true
                                }
                                MotionEvent.ACTION_UP -> {
                                    if (System.currentTimeMillis() - downTime < 250) {
                                        engine.onTap()
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                    }.also { glViewRef = it }
                },
                update = { gl ->
                    glViewRef = gl
                },
                onRelease = { gl ->
                    glViewRef = null
                    gl.destroy()
                    engine.release()
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createVrmWebView(
    context: Context,
    assetLoader: WebViewAssetLoader
): WebView {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
        WebView.setWebContentsDebuggingEnabled(true)
    }
    return WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(View.LAYER_TYPE_NONE, null)
        layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            Log.i("Live2DView-VRM", "WebView layout changed: width=${right - left}, height=${bottom - top}")
        }
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
                    Log.d("Live2DView-VRM", "[${it.messageLevel()}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                }
                return true
            }
        }

        addJavascriptInterface(object {
            @JavascriptInterface
            fun onModelReady() {
                Log.d("Live2DView-VRM", "3D VRM model ready")
            }

            @JavascriptInterface
            fun onAvatarTapped() {
                Log.d("Live2DView-VRM", "3D VRM avatar tapped")
            }
        }, "AriaNative")

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null

                // 1. Direct handling of .vrm with full binary response and CORS headers
                if (url.path?.endsWith(".vrm", ignoreCase = true) == true) {
                    try {
                        val assetPath = url.path!!.trimStart('/').removePrefix("assets/")
                        val isStream = context.assets.open(assetPath)
                        val length = try {
                            context.assets.openFd(assetPath).length.toString()
                        } catch (e: Exception) {
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
                        Log.e("Live2DView-VRM", "Failed direct soltini.vrm load", e)
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
                Log.d("Live2DView-VRM", "Page finished loading: $url")
                view?.evaluateJavascript(
                    "window.dispatchEvent(new Event('resize')); if (window.Aria) { window.Aria.setListening(true); }",
                    null
                )
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                Log.e("Live2DView-VRM", "WebView error: ${error?.description} on ${request?.url}")
            }
        }

        loadUrl("https://appassets.androidplatform.net/assets/avatar/index.html")
    }
}
