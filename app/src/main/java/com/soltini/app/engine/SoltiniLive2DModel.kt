package com.soltini.app.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.CubismModelSettingJson
import com.live2d.sdk.cubism.framework.effect.CubismBreath
import com.live2d.sdk.cubism.framework.effect.CubismEyeBlink
import com.live2d.sdk.cubism.framework.id.CubismId
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.model.CubismUserModel
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid
import java.io.InputStream

/**
 * SoltiniLive2DModel
 *
 * Implements CubismUserModel to load moc3, textures, physics, and drive
 * real-time facial parameters and audio lip-sync.
 */
class SoltiniLive2DModel : CubismUserModel() {

    private val textures = mutableListOf<Int>()
    private var modelSetting: CubismModelSettingJson? = null
    private var modelDir: String = ""

    // Param IDs
    private var idParamAngleX: CubismId? = null
    private var idParamAngleY: CubismId? = null
    private var idParamAngleZ: CubismId? = null
    private var idParamEyeBallX: CubismId? = null
    private var idParamEyeBallY: CubismId? = null
    private var idParamBodyAngleX: CubismId? = null
    private var idParamMouthOpenY: CubismId? = null

    var dragX: Float = 0f
    var dragY: Float = 0f
    var lipSyncValue: Float = 0f

    fun loadAssets(context: Context, dir: String, modelSettingFile: String) {
        modelDir = dir
        try {
            val assetManager = context.assets
            val settingBytes = assetManager.open("$dir/$modelSettingFile").use { it.readBytes() }
            val setting = CubismModelSettingJson(settingBytes)
            modelSetting = setting

            // 1. Load MOC3
            val mocFile = setting.modelFileName
            if (!mocFile.isNullOrEmpty()) {
                val mocBytes = assetManager.open("$dir/$mocFile").use { it.readBytes() }
                loadModel(mocBytes)
            }

            // 2. Load Physics
            val physicsFile = setting.physicsFileName
            if (!physicsFile.isNullOrEmpty()) {
                val physicsBytes = assetManager.open("$dir/$physicsFile").use { it.readBytes() }
                loadPhysics(physicsBytes)
            }

            // 3. Load Pose
            val poseFile = setting.poseFileName
            if (!poseFile.isNullOrEmpty()) {
                val poseBytes = assetManager.open("$dir/$poseFile").use { it.readBytes() }
                loadPose(poseBytes)
            }

            // 4. Setup EyeBlink & Breath
            eyeBlink = CubismEyeBlink.create(setting)
            breath = CubismBreath.create()

            // 5. Setup Param IDs
            idParamAngleX = CubismFramework.getIdManager().getId("ParamAngleX")
            idParamAngleY = CubismFramework.getIdManager().getId("ParamAngleY")
            idParamAngleZ = CubismFramework.getIdManager().getId("ParamAngleZ")
            idParamEyeBallX = CubismFramework.getIdManager().getId("ParamEyeBallX")
            idParamEyeBallY = CubismFramework.getIdManager().getId("ParamEyeBallY")
            idParamBodyAngleX = CubismFramework.getIdManager().getId("ParamBodyAngleX")
            idParamMouthOpenY = CubismFramework.getIdManager().getId("ParamMouthOpenY")

            // 6. Setup Renderer
            setupRenderer(CubismRendererAndroid.create(1080, 1920))
            setupTextures(context)
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Live2D model assets from $dir", e)
        }
    }

    private fun setupTextures(context: Context) {
        val setting = modelSetting ?: return
        val count = setting.textureCount
        for (i in 0 until count) {
            val textureFile = setting.getTextureFileName(i)
            if (!textureFile.isNullOrEmpty()) {
                val textureId = loadTexture(context, "$modelDir/$textureFile")
                val renderer = getRenderer<CubismRendererAndroid>()
                renderer.bindTexture(i, textureId)
                textures.add(textureId)
            }
        }
    }

    private fun loadTexture(context: Context, path: String): Int {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        val textureId = textureIds[0]

        context.assets.open(path).use { stream: InputStream ->
            val bitmap = BitmapFactory.decodeStream(stream)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            bitmap.recycle()
        }
        return textureId
    }

    fun update(deltaTimeSeconds: Float) {
        if (!isInitialized || model == null) return

        model.loadParameters()

        // Eye blink
        eyeBlink?.updateParameters(model, deltaTimeSeconds)

        // Drag angle tracking
        idParamAngleX?.let { model.addParameterValue(it, dragX * 30.0f) }
        idParamAngleY?.let { model.addParameterValue(it, dragY * 30.0f) }
        idParamAngleZ?.let { model.addParameterValue(it, dragX * dragY * -30.0f) }
        idParamEyeBallX?.let { model.addParameterValue(it, dragX) }
        idParamEyeBallY?.let { model.addParameterValue(it, dragY) }
        idParamBodyAngleX?.let { model.addParameterValue(it, dragX * 10.0f) }

        // Audio amplitude lip-sync
        idParamMouthOpenY?.let {
            model.addParameterValue(it, lipSyncValue.coerceIn(0f, 1f))
        }

        // Breathing & Physics
        breath?.updateParameters(model, deltaTimeSeconds)
        physics?.evaluate(model, deltaTimeSeconds)

        model.saveParameters()
    }

    fun draw(matrix: CubismMatrix44) {
        if (!isInitialized || model == null) return
        val renderer = getRenderer<CubismRendererAndroid>() ?: return
        matrix.multiplyByMatrix(modelMatrix)
        renderer.mvpMatrix = matrix
        renderer.drawModel()
    }

    fun release() {
        if (textures.isNotEmpty()) {
            val arr = textures.toIntArray()
            GLES20.glDeleteTextures(arr.size, arr, 0)
            textures.clear()
        }
        deleteRenderer()
        modelSetting = null
        isInitialized = false
    }

    companion object {
        private const val TAG = "SoltiniLive2DModel"
    }
}
