package com.lucy.mate.ui.components

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
import com.lucy.mate.engine.SoltiniGLRenderer
import com.lucy.mate.engine.SoltiniGLTextureView
import com.lucy.mate.engine.SoltiniLive2DEngine
import com.lucy.mate.settings.AppSettings

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
        var webViewRef by remember { mutableStateOf<WebView?>(null) }
        val throttleState = remember {
            object {
                var lastTime = 0L
                var lastSentAmp = -1f
                var lastSpeaking = false
            }
        }

        androidx.compose.runtime.SideEffect {
            val wv = webViewRef ?: return@SideEffect
            val clampedAmp = if (isSpeaking) (amplitude * 2.5f).coerceIn(0f, 1f) else 0f
            val now = android.os.SystemClock.uptimeMillis()
            if (now - throttleState.lastTime >= 33L || kotlin.math.abs(clampedAmp - throttleState.lastSentAmp) > 0.05f || isSpeaking != throttleState.lastSpeaking) {
                throttleState.lastTime = now
                throttleState.lastSentAmp = clampedAmp
                throttleState.lastSpeaking = isSpeaking
                val js = "if (window.Aria) { window.Aria.setTalking($isSpeaking); window.Aria.setMouthAmplitude($clampedAmp); }"
                wv.evaluateJavascript(js, null)
            }
        }

        val nativeBridge = remember {
            object {
                @JavascriptInterface
                fun onModelReady() {
                    Log.d("Live2DView-VRM", "3D VRM model ready")
                }

                @JavascriptInterface
                fun onAvatarTapped() {
                    Log.d("Live2DView-VRM", "3D VRM avatar tapped")
                }
            }
        }

        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    com.lucy.mate.engine.VrmWebViewHelper.createConfiguredWebView(ctx, nativeBridge).also {
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

        // Direct lip-sync update without cancelling and launching coroutines 60 times/sec
        androidx.compose.runtime.SideEffect {
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
