package com.soltini.app.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.soltini.app.engine.SoltiniGLRenderer
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
    val isVrm = characterName == AppSettings.CHARACTER_SOLTINI

    if (isVrm) {
        val webView = remember {
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mediaPlaybackRequiresUserGesture = false
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript("window.Aria && window.Aria.setListening(true);", null)
                    }
                }
                loadUrl("file:///android_asset/avatar/index.html")
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                webView.destroy()
            }
        }

        LaunchedEffect(isSpeaking, amplitude) {
            val clampedAmp = (amplitude * 2.5f).coerceIn(0f, 1f)
            val js = "if (window.Aria) { window.Aria.setTalking($isSpeaking); window.Aria.setMouthAmplitude($clampedAmp); }"
            webView.evaluateJavascript(js, null)
        }

        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    } else {
        val glView = remember(characterName) {
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(2)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                setZOrderMediaOverlay(true)
                setRenderer(SoltiniGLRenderer(context))
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

                var lastX = 0f
                var lastY = 0f
                setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            lastX = event.x
                            lastY = event.y
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.x - lastX) / width.toFloat()
                            val dy = (lastY - event.y) / height.toFloat()
                            SoltiniLive2DEngine.getInstance().onDrag(dx, dy)
                            lastX = event.x
                            lastY = event.y
                            true
                        }
                        else -> false
                    }
                }
            }
        }

        LaunchedEffect(characterName) {
            SoltiniLive2DEngine.getInstance().changeCharacter(context, characterName)
        }

        LaunchedEffect(amplitude, isSpeaking) {
            val lipAmp = if (isSpeaking) (amplitude * 2.5f).coerceIn(0f, 1f) else 0f
            SoltiniLive2DEngine.getInstance().setLipSync(lipAmp)
        }

        DisposableEffect(characterName) {
            onDispose {
                // Keep engine alive across recomposition
            }
        }

        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
        }
    }
}
