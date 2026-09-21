package com.lucy.mate.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.live2d.sdk.cubism.framework.CubismDefaultParameterId
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.CubismModelSettingJson
import com.live2d.sdk.cubism.framework.effect.CubismBreath
import com.live2d.sdk.cubism.framework.effect.CubismEyeBlink
import com.live2d.sdk.cubism.framework.id.CubismId
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.model.CubismUserModel
import com.live2d.sdk.cubism.framework.motion.CubismExpressionMotion
import com.live2d.sdk.cubism.framework.motion.CubismMotion
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid
import java.io.InputStream
import android.content.res.AssetManager
import java.util.Random

/**
 * SoltiniLive2DModel
 *
 * Full-fidelity CubismUserModel implementation matching production S40:
 * - Loads model moc3, expressions, physics, pose, user data, and motions.
 * - Applies layout map to modelMatrix.
 * - Generates textures with pre-multiplied alpha on the GL thread.
 * - Drives eye blink, breath, physics, drag angles, and audio lip-sync.
 * - Plays idle and tap motions.
 * - Invokes model.update() on every frame to commit vertex data to GPU.
 */
class SoltiniLive2DModel : CubismUserModel() {

    private val textureIds = mutableListOf<Int>()
    private val eyeBlinkParameterIds = mutableListOf<CubismId>()
    private val lipSyncParameterIds = mutableListOf<CubismId>()
    private val motions = HashMap<String, CubismMotion>()
    private val expressions = HashMap<String, CubismExpressionMotion>()
    private val expressionNames = mutableListOf<String>()
    private val random = Random()

    private var defaultMouthOpenId: CubismId? = null
    private val idAngleX: CubismId = CubismFramework.getIdManager().getId(CubismDefaultParameterId.ParameterId.ANGLE_X.id)
    private val idAngleY: CubismId = CubismFramework.getIdManager().getId(CubismDefaultParameterId.ParameterId.ANGLE_Y.id)
    private val idAngleZ: CubismId = CubismFramework.getIdManager().getId(CubismDefaultParameterId.ParameterId.ANGLE_Z.id)
    private val idBodyAngleX: CubismId = CubismFramework.getIdManager().getId(CubismDefaultParameterId.ParameterId.BODY_ANGLE_X.id)
    private val idEyeBallX: CubismId = CubismFramework.getIdManager().getId("ParamEyeBallX")
    private val idEyeBallY: CubismId = CubismFramework.getIdManager().getId("ParamEyeBallY")

    private val mvpMatrix = CubismMatrix44.create()

    @Volatile var dragX: Float = 0f
    @Volatile var dragY: Float = 0f
    @Volatile var targetLipSyncValue: Float = 0f
    private var smoothedLipSyncValue: Float = 0f
    private var idleTimer: Float = 0f

    fun load(context: Context, dir: String, modelSettingFileName: String, viewportWidth: Int, viewportHeight: Int): Boolean {
        try {
            val assetManager = context.assets
            val settingBytes = readAsset(assetManager, "$dir/$modelSettingFileName")
            if (settingBytes.isEmpty()) {
                Log.e(TAG, "Cannot read setting file: $dir/$modelSettingFileName")
                return false
            }

            val setting = CubismModelSettingJson(settingBytes)
            isUpdated(true)
            isInitialized(false)

            // 1. Model MOC3
            val mocFileName = setting.modelFileName
            if (!mocFileName.isNullOrEmpty()) {
                val mocBytes = readAsset(assetManager, "$dir/$mocFileName")
                loadModel(mocBytes, mocConsistency)
            }

            if (model == null) {
                Log.e(TAG, "Failed to load model MOC3 from $dir")
                return false
            }

            // 2. Expressions
            val expressionCount = setting.expressionCount
            for (i in 0 until expressionCount) {
                val expName = setting.getExpressionName(i)
                val expFile = setting.getExpressionFileName(i)
                if (!expFile.isNullOrEmpty()) {
                    val expBytes = readAsset(assetManager, "$dir/$expFile")
                    val expMotion = loadExpression(expBytes)
                    if (expMotion != null) {
                        expressions[expName] = expMotion
                        if (!expressionNames.contains(expName)) {
                            expressionNames.add(expName)
                        }
                    }
                }
            }

            // 3. Physics
            val physicsFile = setting.physicsFileName
            if (!physicsFile.isNullOrEmpty()) {
                val physicsBytes = readAsset(assetManager, "$dir/$physicsFile")
                loadPhysics(physicsBytes)
            }

            // 4. Pose
            val poseFile = setting.poseFileName
            if (!poseFile.isNullOrEmpty()) {
                val poseBytes = readAsset(assetManager, "$dir/$poseFile")
                loadPose(poseBytes)
            }

            // 5. Eye Blink
            if (setting.eyeBlinkParameterCount > 0) {
                eyeBlink = CubismEyeBlink.create(setting)
            }

            // 6. Breath
            breath = CubismBreath.create()
            val breathParams = ArrayList<CubismBreath.BreathParameterData>().apply {
                add(CubismBreath.BreathParameterData(idAngleX, 0.0f, 15.0f, 6.5345f, 0.5f))
                add(CubismBreath.BreathParameterData(idAngleY, 0.0f, 8.0f, 3.5345f, 0.5f))
                add(CubismBreath.BreathParameterData(idAngleZ, 0.0f, 10.0f, 5.5345f, 0.5f))
                add(CubismBreath.BreathParameterData(idBodyAngleX, 0.0f, 4.0f, 15.5345f, 0.5f))
                add(CubismBreath.BreathParameterData(
                    CubismFramework.getIdManager().getId(CubismDefaultParameterId.ParameterId.BREATH.id),
                    0.5f, 0.5f, 3.2345f, 0.5f
                ))
            }
            breath.setParameters(breathParams)

            // 7. User Data
            val userDataFile = setting.userDataFile
            if (!userDataFile.isNullOrEmpty()) {
                val userDataBytes = readAsset(assetManager, "$dir/$userDataFile")
                loadUserData(userDataBytes)
            }

            // 8. Blink and LipSync parameter IDs
            eyeBlinkParameterIds.clear()
            for (i in 0 until setting.eyeBlinkParameterCount) {
                setting.getEyeBlinkParameterId(i)?.let { eyeBlinkParameterIds.add(it) }
            }

            lipSyncParameterIds.clear()
            for (i in 0 until setting.lipSyncParameterCount) {
                setting.getLipSyncParameterId(i)?.let { lipSyncParameterIds.add(it) }
            }
            if (lipSyncParameterIds.isEmpty()) {
                defaultMouthOpenId = CubismFramework.getIdManager().getId(CubismDefaultParameterId.ParameterId.MOUTH_OPEN_Y.id)
            }

            // 9. Layout map
            val layoutMap = HashMap<String, Float>()
            if (setting.getLayoutMap(layoutMap)) {
                modelMatrix.setupFromLayout(layoutMap)
            }

            model.saveParameters()

            // 10. Motions
            val motionGroupCount = setting.motionGroupCount
            for (i in 0 until motionGroupCount) {
                val groupName = setting.getMotionGroupName(i)
                val count = setting.getMotionCount(groupName)
                for (j in 0 until count) {
                    val motionKey = "${groupName}_$j"
                    val motionFile = setting.getMotionFileName(groupName, j)
                    if (!motionFile.isNullOrEmpty()) {
                        try {
                            val motionBytes = readAsset(assetManager, "$dir/$motionFile")
                            val cubismMotion = loadMotion(motionBytes, motionConsistency)
                            if (cubismMotion != null) {
                                val fadeIn = setting.getMotionFadeInTimeValue(groupName, j)
                                if (fadeIn != -1.0f) cubismMotion.fadeInTime = fadeIn
                                val fadeOut = setting.getMotionFadeOutTimeValue(groupName, j)
                                if (fadeOut != -1.0f) cubismMotion.fadeOutTime = fadeOut
                                cubismMotion.setEffectIds(eyeBlinkParameterIds, lipSyncParameterIds)
                                motions[motionKey] = cubismMotion
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed loading motion $dir/$motionFile: ${t.message}")
                        }
                    }
                }
            }

            motionManager.stopAllMotions()

            // 11. Renderer & Textures on GL thread
            setupRenderer(CubismRendererAndroid.create(viewportWidth, viewportHeight))
            val renderer = getRenderer<CubismRendererAndroid>()

            textureIds.clear()
            val textureCount = setting.textureCount
            val options = BitmapFactory.Options().apply {
                inScaled = false
                inPremultiplied = true
            }

            for (i in 0 until textureCount) {
                val textureFile = setting.getTextureFileName(i)
                if (!textureFile.isNullOrEmpty()) {
                    val texturePath = "$dir/$textureFile"
                    val bitmap = getOrDecodeTexture(assetManager, texturePath, options)
                    if (bitmap != null) {
                        val ids = IntArray(1)
                        GLES20.glGenTextures(1, ids, 0)
                        val texId = ids[0]
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_NEAREST)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

                        renderer.bindTexture(i, texId)
                        renderer.isPremultipliedAlpha(true)
                        textureIds.add(texId)
                    }
                }
            }

            isUpdated(false)
            isInitialized(true)
            startRandomMotion("Idle", 1)
            Log.d(TAG, "Successfully loaded Live2D model from $dir with ${textureIds.size} textures")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Exception loading Live2D model from $dir", t)
            return false
        }
    }

    private fun readAsset(assetManager: AssetManager, path: String): ByteArray {
        return assetByteCache.getOrPut(path) {
            try {
                assetManager.open(path).use { it.readBytes() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed reading asset: $path", e)
                ByteArray(0)
            }
        }
    }

    fun startRandomMotion(groupName: String, priority: Int) {
        val matchingKeys = motions.keys.filter { it.startsWith("${groupName}_") }
        if (matchingKeys.isEmpty()) return

        if (priority == 3) {
            motionManager.setReservationPriority(priority)
        } else if (!motionManager.reserveMotion(priority)) {
            return
        }

        val key = matchingKeys[random.nextInt(matchingKeys.size)]
        motions[key]?.let { motion ->
            motionManager.startMotionPriority(motion, priority)
        }
    }

    fun startRandomExpression() {
        if (expressionNames.isEmpty()) return
        val name = expressionNames[random.nextInt(expressionNames.size)]
        expressions[name]?.let { exp ->
            expressionManager?.startMotionPriority(exp, 3)
        }
    }

    fun update(deltaTimeSeconds: Float) {
        if (!isInitialized || model == null) return
        try {
            model.loadParameters()

            // Idle motion loop
            if (motionManager.isFinished) {
                startRandomMotion("Idle", 1)
            } else {
                motionManager.updateMotion(model, deltaTimeSeconds)
            }
            model.saveParameters()

            expressionManager?.updateMotion(model, deltaTimeSeconds)

            // Periodic idle tap / expression — only when not actively speaking
            val isModelSpeaking = targetLipSyncValue > 0.05f || smoothedLipSyncValue > 0.05f
            if (!isModelSpeaking) {
                idleTimer += deltaTimeSeconds
                if (idleTimer >= 8.0f) {
                    idleTimer = 0f
                    startRandomMotion("TapBody", 2)
                    if (random.nextFloat() < 0.6f) {
                        startRandomExpression()
                    }
                }
            } else {
                idleTimer = 0f
            }

            // Drag tracking
            idAngleX.let { model.addParameterValue(it, dragX * 30.0f) }
            idAngleY.let { model.addParameterValue(it, dragY * 30.0f) }
            idAngleZ.let { model.addParameterValue(it, dragX * dragY * -30.0f) }
            idEyeBallX.let { model.addParameterValue(it, dragX) }
            idEyeBallY.let { model.addParameterValue(it, dragY) }
            idBodyAngleX.let { model.addParameterValue(it, dragX * 10.0f) }

            // Lip-sync with smoothing (lerp)
            val targetAmp = targetLipSyncValue.coerceIn(0f, 1f)
            val lerpFactor = if (targetAmp > smoothedLipSyncValue) 0.55f else 0.3f
            smoothedLipSyncValue = smoothedLipSyncValue + (targetAmp - smoothedLipSyncValue) * lerpFactor

            if (lipSyncParameterIds.isNotEmpty()) {
                for (paramId in lipSyncParameterIds) {
                    model.addParameterValue(paramId, smoothedLipSyncValue, 0.8f)
                }
            } else {
                defaultMouthOpenId?.let {
                    model.addParameterValue(it, smoothedLipSyncValue, 0.8f)
                }
            }

            // Eye blink, breath, physics, pose
            eyeBlink?.updateParameters(model, deltaTimeSeconds)
            breath?.updateParameters(model, deltaTimeSeconds)
            physics?.evaluate(model, deltaTimeSeconds)
            pose?.updateParameters(model, deltaTimeSeconds)

            // CRITICAL: Commit parameter changes to vertex coordinates!
            model.update()
        } catch (t: Throwable) {
            Log.e(TAG, "Exception updating Live2D model: ${t.message}", t)
        }
    }

    fun draw(projectionMatrix: CubismMatrix44) {
        if (!isInitialized || model == null) return
        try {
            val renderer = getRenderer<CubismRendererAndroid>() ?: return

            mvpMatrix.loadIdentity()
            CubismMatrix44.multiply(modelMatrix.array, projectionMatrix.array, mvpMatrix.array)
            renderer.mvpMatrix = mvpMatrix
            renderer.drawModel()
        } catch (t: Throwable) {
            Log.e(TAG, "Exception drawing Live2D model: ${t.message}", t)
        }
    }

    fun destroy() {
        if (textureIds.isNotEmpty()) {
            val arr = textureIds.toIntArray()
            GLES20.glDeleteTextures(arr.size, arr, 0)
            textureIds.clear()
        }
        delete()
        isInitialized = false
    }

    companion object {
        private const val TAG = "SoltiniLive2DModel"
        private val assetByteCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        private val textureBitmapCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

        private fun getOrDecodeTexture(assetManager: AssetManager, path: String, options: BitmapFactory.Options): Bitmap? {
            textureBitmapCache[path]?.let {
                if (!it.isRecycled) return it
            }
            return try {
                assetManager.open(path).use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }?.also {
                    textureBitmapCache[path] = it
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed decoding texture: $path", e)
                null
            }
        }

        /**
         * Preloads all model assets (JSONs, MOC3, motions, expressions) and decodes texture Bitmaps
         * into cache on the calling thread (intended for ModelLoaderDispatcher).
         */
        fun preloadAssets(context: Context, dir: String, modelSettingFileName: String) {
            try {
                val assetManager = context.assets
                val settingBytes = assetByteCache.getOrPut("$dir/$modelSettingFileName") {
                    try {
                        assetManager.open("$dir/$modelSettingFileName").use { it.readBytes() }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed reading setting asset: $dir/$modelSettingFileName", e)
                        ByteArray(0)
                    }
                }
                if (settingBytes.isEmpty()) return

                val setting = CubismModelSettingJson(settingBytes)

                // 1. Model MOC3
                val mocFileName = setting.modelFileName
                if (!mocFileName.isNullOrEmpty()) {
                    val path = "$dir/$mocFileName"
                    if (!assetByteCache.containsKey(path)) {
                        try {
                            assetByteCache[path] = assetManager.open(path).use { it.readBytes() }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed reading moc3 $path: ${e.message}")
                        }
                    }
                }

                // 2. Expressions
                for (i in 0 until setting.expressionCount) {
                    val expFile = setting.getExpressionFileName(i)
                    if (!expFile.isNullOrEmpty()) {
                        val path = "$dir/$expFile"
                        if (!assetByteCache.containsKey(path)) {
                            try {
                                assetByteCache[path] = assetManager.open(path).use { it.readBytes() }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed reading expression $path: ${e.message}")
                            }
                        }
                    }
                }

                // 3. Physics
                val physicsFile = setting.physicsFileName
                if (!physicsFile.isNullOrEmpty()) {
                    val path = "$dir/$physicsFile"
                    if (!assetByteCache.containsKey(path)) {
                        try {
                            assetByteCache[path] = assetManager.open(path).use { it.readBytes() }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed reading physics $path: ${e.message}")
                        }
                    }
                }

                // 4. Pose
                val poseFile = setting.poseFileName
                if (!poseFile.isNullOrEmpty()) {
                    val path = "$dir/$poseFile"
                    if (!assetByteCache.containsKey(path)) {
                        try {
                            assetByteCache[path] = assetManager.open(path).use { it.readBytes() }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed reading pose $path: ${e.message}")
                        }
                    }
                }

                // 5. User Data
                val userDataFile = setting.userDataFile
                if (!userDataFile.isNullOrEmpty()) {
                    val path = "$dir/$userDataFile"
                    if (!assetByteCache.containsKey(path)) {
                        try {
                            assetByteCache[path] = assetManager.open(path).use { it.readBytes() }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed reading userData $path: ${e.message}")
                        }
                    }
                }

                // 6. Motions
                for (i in 0 until setting.motionGroupCount) {
                    val groupName = setting.getMotionGroupName(i)
                    val count = setting.getMotionCount(groupName)
                    for (j in 0 until count) {
                        val motionFile = setting.getMotionFileName(groupName, j)
                        if (!motionFile.isNullOrEmpty()) {
                            val path = "$dir/$motionFile"
                            if (!assetByteCache.containsKey(path)) {
                                try {
                                    assetByteCache[path] = assetManager.open(path).use { it.readBytes() }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed reading motion $path: ${e.message}")
                                }
                            }
                        }
                    }
                }

                // 7. Textures (decode bitmaps in background on ModelLoader thread)
                val options = BitmapFactory.Options().apply {
                    inScaled = false
                    inPremultiplied = true
                }
                for (i in 0 until setting.textureCount) {
                    val textureFile = setting.getTextureFileName(i)
                    if (!textureFile.isNullOrEmpty()) {
                        val texturePath = "$dir/$textureFile"
                        getOrDecodeTexture(assetManager, texturePath, options)
                    }
                }
                Log.d(TAG, "Preloaded assets and textures for $dir on background thread")
            } catch (t: Throwable) {
                Log.w(TAG, "Error preloading assets for $dir: ${t.message}")
            }
        }
    }
}
