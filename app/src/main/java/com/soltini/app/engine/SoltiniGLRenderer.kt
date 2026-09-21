package com.soltini.app.engine

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * SoltiniGLRenderer
 *
 * Translucent OpenGL ES 2.0 surface renderer driving the SoltiniLive2DEngine.
 */
class SoltiniGLRenderer(
    private val context: Context,
    val engine: SoltiniLive2DEngine = SoltiniLive2DEngine.getInstance()
) : GLSurfaceView.Renderer {

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        engine.onSurfaceCreated(context)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        engine.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        engine.onDrawFrame(context)
    }
}
