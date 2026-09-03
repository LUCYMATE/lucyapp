package com.soltini.app.engine

import android.content.Context
import android.util.Log
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.math.CubismViewMatrix
import com.soltini.app.settings.AppSettings

/**
 * SoltiniLive2DEngine
 *
 * Singleton managing Live2D Cubism runtime state:
 * - Initializes CubismFramework
 * - Loads and switches active characters (Maya, Friday, Venom)
 * - Drives frame render loop and viewport projection
 * - Dispatches touch drag and audio lip-sync
 */
class SoltiniLive2DEngine private constructor() {

    private var currentModel: SoltiniLive2DModel? = null
    private var pendingCharacter: String? = null
    private var isFrameworkInitialized = false
    private var lastUpdateTime = 0L

    val viewMatrix = CubismViewMatrix()
    private val projectionMatrix = CubismMatrix44.create()

    fun initialize(context: Context) {
        if (isFrameworkInitialized) return

        val option = CubismFramework.Option()
        CubismFramework.startUp(option)
        CubismFramework.initialize()
        isFrameworkInitialized = true
        Log.d(TAG, "Live2D Cubism Framework initialized successfully")
    }

    fun onSurfaceCreated(context: Context) {
        initialize(context)
        val charToLoad = pendingCharacter ?: AppSettings.CHARACTER_MAYA
        loadCharacter(context, charToLoad)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        val ratio = width.toFloat() / height.toFloat()
        projectionMatrix.loadIdentity()
        if (ratio > 1.0f) {
            projectionMatrix.scale(1.0f / ratio, 1.0f)
        } else {
            projectionMatrix.scale(1.0f, ratio)
        }
    }

    fun onDrawFrame() {
        val now = System.currentTimeMillis()
        val deltaTime = if (lastUpdateTime == 0L) 0.016f else (now - lastUpdateTime) / 1000f
        lastUpdateTime = now

        currentModel?.let { model ->
            model.update(deltaTime.coerceIn(0.001f, 0.1f))
            val matrix = CubismMatrix44.create()
            matrix.setMatrix(projectionMatrix.array)
            matrix.multiplyByMatrix(viewMatrix)
            model.draw(matrix)
        }
    }

    fun changeCharacter(context: Context, characterName: String) {
        pendingCharacter = characterName
        loadCharacter(context, characterName)
    }

    private fun loadCharacter(context: Context, characterName: String) {
        currentModel?.release()
        val newModel = SoltiniLive2DModel()

        when (characterName) {
            AppSettings.CHARACTER_MAYA -> {
                newModel.loadAssets(context, "live2d/maya", "Mao.model3.json")
            }
            AppSettings.CHARACTER_FRIDAY -> {
                newModel.loadAssets(context, "live2d/friday", "Haru.model3.json")
            }
            AppSettings.CHARACTER_VENOM -> {
                newModel.loadAssets(context, "live2d/venom", "Natori.model3.json")
            }
            else -> {
                newModel.loadAssets(context, "live2d/maya", "Mao.model3.json")
            }
        }
        currentModel = newModel
    }

    fun setLipSync(amplitude: Float) {
        currentModel?.lipSyncValue = amplitude
    }

    fun onDrag(dx: Float, dy: Float) {
        currentModel?.let {
            it.dragX = (it.dragX + dx).coerceIn(-1f, 1f)
            it.dragY = (it.dragY + dy).coerceIn(-1f, 1f)
        }
    }

    fun release() {
        currentModel?.release()
        currentModel = null
        if (isFrameworkInitialized) {
            CubismFramework.dispose()
            isFrameworkInitialized = false
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
