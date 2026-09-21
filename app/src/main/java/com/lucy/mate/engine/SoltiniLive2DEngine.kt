package com.lucy.mate.engine

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.ICubismLoadFileFunction
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.rendering.android.CubismOffscreenManagerAndroid
import com.live2d.sdk.cubism.framework.rendering.android.CubismShaderAndroid
import com.lucy.mate.core.SoltiniDispatchers
import com.lucy.mate.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * SoltiniLive2DEngine
 *
 * Thread-safe singleton managing the native Live2D Cubism runtime:
 * - Guarantees all OpenGL operations (model loading, shader creation, texture generation)
 *   occur STRICTLY on the GL rendering thread within onDrawFrame.
 * - Manages offscreen framebuffers and clipping masks via CubismOffscreenManagerAndroid.
 * - Computes dynamic canvas aspect ratio scaling matching C4925zX.
 * - Drives lip-sync and drag interactions with smooth interpolation.
 */
class SoltiniLive2DEngine(
    val engineTag: String = "Default",
    initialCharacter: String = AppSettings.CHARACTER_MAYA
) {

    @Volatile private var currentModel: SoltiniLive2DModel? = null
    @Volatile private var pendingCharacter: String = initialCharacter
    private var activeCharacter: String? = null

    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0
    private var lastFrameTime: Long = 0L

    private val projectionMatrix = CubismMatrix44.create()

    private val renderLock = Any()

    fun onSurfaceCreated(context: Context) {
        preloadCharacter(context, pendingCharacter)
        synchronized(renderLock) {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            if (!CubismFramework.isInitialized()) {
                synchronized(CubismFramework::class.java) {
                    if (!CubismFramework.isInitialized()) {
                        val option = CubismFramework.Option().apply {
                            loadFileFunction = ICubismLoadFileFunction { filePath: String? ->
                                try {
                                    if (filePath != null) {
                                        context.assets.open(filePath).use { it.readBytes() }
                                    } else {
                                        ByteArray(0)
                                    }
                                } catch (e: Exception) {
                                    ByteArray(0)
                                }
                            }
                        }
                        CubismFramework.startUp(option)
                        CubismFramework.initialize()
                        Log.d(TAG, "[$engineTag] Live2D Cubism Framework initialized successfully")
                    }
                }
            }

            try {
                CubismShaderAndroid.getInstance().releaseInvalidShaderProgram()
                CubismShaderAndroid.deleteInstance()
            } catch (t: Throwable) {
                Log.w(TAG, "Notice during shader reload: ${t.message}")
            }

            currentModel?.destroy()
            currentModel = null
            activeCharacter = null
            lastFrameTime = 0L
        }
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        synchronized(renderLock) {
            viewportWidth = width
            viewportHeight = height
            GLES20.glViewport(0, 0, width, height)
        }
    }

    fun onDrawFrame(context: Context) {
        synchronized(renderLock) {
            if (viewportWidth <= 0 || viewportHeight <= 0) return

            val charToLoad = pendingCharacter
            if (activeCharacter != charToLoad) {
                val (dir, file) = getModelAssetInfo(charToLoad)
                val newModel = SoltiniLive2DModel()
                val success = try {
                    newModel.load(context, dir, file, viewportWidth, viewportHeight)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed loading Live2D character $charToLoad", t)
                    false
                }
                if (success) {
                    val oldModel = currentModel
                    currentModel = newModel
                    activeCharacter = charToLoad
                    oldModel?.destroy()
                    CubismOffscreenManagerAndroid.getInstance().releaseStaleRenderTextures()
                    Log.d(TAG, "Switched active Live2D character to $charToLoad ($dir/$file)")
                } else {
                    activeCharacter = charToLoad
                    Log.e(TAG, "Failed to switch Live2D character to $charToLoad")
                }
            }

            val now = System.nanoTime()
            val deltaTime = if (lastFrameTime == 0L) 0.016f else ((now - lastFrameTime) / 1e9f).coerceIn(0.001f, 0.1f)
            lastFrameTime = now

            // Live2D requires depth test disabled for proper 2D sorting & zero Z-fighting/blinking
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val model = currentModel ?: return
            if (!model.isInitialized) return

            try {
                // 1. Begin offscreen frame process for clipping masks
                CubismOffscreenManagerAndroid.getInstance().beginFrameProcess()

                // 2. Update model animations, physics, and lip-sync
                model.update(deltaTime)

                // 3. Compute viewport & aspect ratio projection matrix
                projectionMatrix.loadIdentity()
                if (viewportWidth > 0 && viewportHeight > 0) {
                    val fWidth = viewportWidth.toFloat()
                    val fHeight = viewportHeight.toFloat()
                    val fAspect = fWidth / fHeight

                    if (engineTag == "Overlay") {
                        // Default original overlay projection - untouched so floating overlay fits perfectly
                        if (fWidth > fHeight) {
                            projectionMatrix.scale(1.0f / fAspect, 1.0f)
                        } else {
                            projectionMatrix.scale(1.0f, fAspect)
                        }
                    } else {
                        // HomeView: gentle 0.85 scale with 1:1 true aspect ratio so head/hat and feet fit with zero clipping
                        val scale = 0.85f
                        if (fWidth > fHeight) {
                            projectionMatrix.scale(scale / fAspect, scale)
                        } else {
                            projectionMatrix.scale(scale, fAspect * scale)
                        }
                    }
                }

                // 4. Draw model
                model.draw(projectionMatrix)

                // 5. End offscreen process (do NOT thrash FBOs with per-frame releaseStaleRenderTextures)
                CubismOffscreenManagerAndroid.getInstance().endFrameProcess()
            } catch (t: Throwable) {
                Log.e(TAG, "Error in onDrawFrame rendering pipeline: ${t.message}", t)
            }
        }
    }

    /**
     * Non-blocking character request from UI thread.
     * All OpenGL allocations and deallocations are deferred to onDrawFrame on the GL thread.
     * Optionally accepts context to preload assets on dedicated ModelLoader thread immediately.
     */
    fun requestCharacter(characterName: String, context: Context? = null) {
        val normalized = when {
            characterName.contains("Maya", ignoreCase = true) -> AppSettings.CHARACTER_MAYA
            characterName.contains("Friday", ignoreCase = true) -> AppSettings.CHARACTER_FRIDAY
            characterName.contains("Venom", ignoreCase = true) -> AppSettings.CHARACTER_VENOM
            else -> AppSettings.CHARACTER_MAYA
        }
        if (pendingCharacter != normalized) {
            pendingCharacter = normalized
            Log.d(TAG, "Live2D character requested: $normalized (from '$characterName')")
        }
        if (context != null) {
            preloadCharacter(context, normalized)
        }
    }

    /**
     * Preloads model assets and decodes texture bitmaps on the dedicated Soltini-ModelLoader thread.
     */
    fun preloadCharacter(context: Context, character: String = pendingCharacter) {
        val (dir, file) = getModelAssetInfo(character)
        CoroutineScope(SoltiniDispatchers.ModelLoaderDispatcher).launch {
            SoltiniLive2DModel.preloadAssets(context.applicationContext, dir, file)
        }
    }

    private fun getModelAssetInfo(character: String): Pair<String, String> {
        return when (character) {
            AppSettings.CHARACTER_MAYA -> "live2d/maya" to "Mao.model3.json"
            AppSettings.CHARACTER_FRIDAY -> "live2d/friday" to "Haru.model3.json"
            AppSettings.CHARACTER_VENOM -> "live2d/venom" to "Natori.model3.json"
            else -> "live2d/maya" to "Mao.model3.json"
        }
    }

    fun setLipSync(amplitude: Float) {
        currentModel?.targetLipSyncValue = amplitude
    }

    fun onDrag(dx: Float, dy: Float) {
        currentModel?.let {
            it.dragX = (it.dragX + dx).coerceIn(-1f, 1f)
            it.dragY = (it.dragY + dy).coerceIn(-1f, 1f)
        }
    }

    fun onTap() {
        currentModel?.let {
            it.startRandomMotion("TapBody", 2)
            it.startRandomExpression()
        }
    }

    fun release() {
        synchronized(renderLock) {
            currentModel?.destroy()
            currentModel = null
            activeCharacter = null
            try {
                CubismShaderAndroid.deleteInstance()
                CubismOffscreenManagerAndroid.releaseInstance()
            } catch (ignored: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "SoltiniLive2DEngine"

        @Volatile
        private var instance: SoltiniLive2DEngine? = null

        fun getInstance(): SoltiniLive2DEngine {
            return instance ?: synchronized(this) {
                instance ?: SoltiniLive2DEngine().also { instance = it }
            }
        }
    }
}
