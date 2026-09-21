package com.soltini.app.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.os.Process
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * SoltiniGLTextureView
 *
 * A hardware-accelerated TextureView with an OpenGL ES 2.0 EGL rendering thread.
 * Unlike GLSurfaceView, TextureView participates directly in the Android View tree
 * and Jetpack Compose composition. With isOpaque = false, transparent pixels
 * (glClearColor(0,0,0,0)) blend seamlessly onto the underlying Compose background
 * without punching black holes or requiring z-order overlay hacks.
 *
 * Modeled after the reference implementation from the decompiled Maya app
 * (AbstractTextureViewSurfaceTextureListenerC2798kJ & C2658jJ).
 */
class SoltiniGLTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private var renderer: GLSurfaceView.Renderer? = null
    private var renderThread: RenderThread? = null

    init {
        // Crucial: Tells Android hardware compositor this view has per-pixel transparency
        isOpaque = false
        surfaceTextureListener = this
    }

    fun setRenderer(renderer: GLSurfaceView.Renderer) {
        this.renderer = renderer
    }

    fun setActiveFrameRate(active: Boolean) {
        renderThread?.setFrameDelay(if (active) 16 else 33)
    }

    fun setPaused(paused: Boolean) {
        renderThread?.setPaused(paused)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val r = renderer ?: return
        var thread = renderThread
        if (thread != null && thread.isAlive) {
            thread.requestStop()
            try {
                thread.join(500)
            } catch (ignored: InterruptedException) {}
        }
        thread = RenderThread(r)
        renderThread = thread
        thread.start()
        thread.onSurfaceAvailable(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderThread?.onSurfaceSizeChanged(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        destroy()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // Handled by EGL swap buffers
    }

    override fun onDetachedFromWindow() {
        destroy()
        super.onDetachedFromWindow()
    }

    fun destroy() {
        renderThread?.let {
            it.requestStop()
            try {
                it.join(1000)
            } catch (ignored: InterruptedException) {
            }
            renderThread = null
        }
    }

    private class RenderThread(
        private val renderer: GLSurfaceView.Renderer
    ) : Thread("SoltiniGLTextureThread") {

        private val lock = Object()
        @Volatile private var isRunning = true
        @Volatile private var frameDelayMs = 16

        private var pendingSurfaceTexture: SurfaceTexture? = null
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var surfaceSizeChanged = false
        private var surfaceDestroyRequested = false
        private var isPaused = false

        private var egl: EGL10? = null
        private var eglDisplay: EGLDisplay? = null
        private var eglConfig: EGLConfig? = null
        private var eglContext: EGLContext? = null
        private var eglSurface: EGLSurface? = null
        private var hasEglSurface = false
        private var surfaceCreatedFired = false

        fun setFrameDelay(delayMs: Int) {
            frameDelayMs = delayMs
        }

        fun setPaused(paused: Boolean) {
            synchronized(lock) {
                if (isPaused != paused) {
                    isPaused = paused
                    if (!paused) {
                        lock.notifyAll()
                    }
                }
            }
        }

        fun onSurfaceAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            synchronized(lock) {
                pendingSurfaceTexture = surface
                surfaceWidth = width
                surfaceHeight = height
                surfaceSizeChanged = true
                lock.notifyAll()
            }
        }

        fun onSurfaceSizeChanged(width: Int, height: Int) {
            synchronized(lock) {
                surfaceWidth = width
                surfaceHeight = height
                surfaceSizeChanged = true
                lock.notifyAll()
            }
        }

        fun onSurfaceDestroyed() {
            synchronized(lock) {
                surfaceDestroyRequested = true
                lock.notifyAll()
                while (hasEglSurface && isRunning) {
                    try {
                        lock.wait(500)
                        break
                    } catch (ignored: InterruptedException) {
                        break
                    }
                }
            }
        }

        fun requestStop() {
            synchronized(lock) {
                isRunning = false
                lock.notifyAll()
            }
        }

        private fun initEGL(): Boolean {
            val eglInstance = EGLContext.getEGL() as? EGL10 ?: return false
            egl = eglInstance

            val display = eglInstance.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            if (display == EGL10.EGL_NO_DISPLAY) return false
            eglDisplay = display

            val version = IntArray(2)
            if (!eglInstance.eglInitialize(display, version)) return false

            val configSpec = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8, // 8-bit Alpha for translucent blending
                EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_STENCIL_SIZE, 8,
                EGL10.EGL_NONE
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!eglInstance.eglChooseConfig(display, configSpec, configs, 1, numConfigs) || numConfigs[0] <= 0) {
                Log.e("SoltiniGLTexture", "eglChooseConfig failed")
                return false
            }

            val config = configs[0] ?: return false
            eglConfig = config

            val contextAttribs = intArrayOf(
                0x3098, 2, // EGL_CONTEXT_CLIENT_VERSION = 2
                EGL10.EGL_NONE
            )
            val context = eglInstance.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttribs)
            if (context == null || context == EGL10.EGL_NO_CONTEXT) {
                Log.e("SoltiniGLTexture", "eglCreateContext failed")
                return false
            }
            eglContext = context
            return true
        }

        private fun createSurface(surfaceTexture: SurfaceTexture): Boolean {
            val eglInstance = egl ?: return false
            val display = eglDisplay ?: return false
            val config = eglConfig ?: return false
            val ctx = eglContext ?: return false

            val surface = eglInstance.eglCreateWindowSurface(display, config, surfaceTexture, null)
            if (surface == null || surface == EGL10.EGL_NO_SURFACE) {
                hasEglSurface = false
                return false
            }
            eglSurface = surface

            if (!eglInstance.eglMakeCurrent(display, surface, surface, ctx)) {
                hasEglSurface = false
                return false
            }

            hasEglSurface = true
            if (!surfaceCreatedFired) {
                renderer.onSurfaceCreated(null, config)
                surfaceCreatedFired = true
            }
            return true
        }

        private fun destroySurface() {
            val eglInstance = egl
            val display = eglDisplay
            val surface = eglSurface
            if (eglInstance != null && display != null && surface != null && surface != EGL10.EGL_NO_SURFACE) {
                eglInstance.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
                eglInstance.eglDestroySurface(display, surface)
            }
            eglSurface = null
            hasEglSurface = false
        }

        private fun destroyContext() {
            val eglInstance = egl
            val display = eglDisplay
            val ctx = eglContext
            if (eglInstance != null && display != null && ctx != null) {
                try {
                    eglInstance.eglDestroyContext(display, ctx)
                } catch (ignored: Throwable) {}
            }
            eglContext = null

            if (eglInstance != null && display != null) {
                try {
                    eglInstance.eglTerminate(display)
                } catch (ignored: Throwable) {}
            }
            eglDisplay = null
        }

        override fun run() {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            } catch (ignored: Throwable) {}

            if (!initEGL()) {
                destroySurface()
                destroyContext()
                return
            }

            while (isRunning) {
                var needSizeChange = false
                var curW = 0
                var curH = 0

                synchronized(lock) {
                    if (surfaceDestroyRequested) {
                        destroySurface()
                        surfaceDestroyRequested = false
                        lock.notifyAll()
                    }

                    val st = pendingSurfaceTexture
                    if (st != null) {
                        if (hasEglSurface) {
                            destroySurface()
                        }
                        createSurface(st)
                        pendingSurfaceTexture = null
                        surfaceSizeChanged = true
                    }

                    while (isRunning && (!hasEglSurface || isPaused)) {
                        try {
                            lock.wait()
                        } catch (ignored: InterruptedException) {}

                        if (surfaceDestroyRequested) {
                            destroySurface()
                            surfaceDestroyRequested = false
                            lock.notifyAll()
                        }

                        val stWait = pendingSurfaceTexture
                        if (stWait != null) {
                            if (hasEglSurface) {
                                destroySurface()
                            }
                            createSurface(stWait)
                            pendingSurfaceTexture = null
                            surfaceSizeChanged = true
                        }
                    }

                    if (surfaceSizeChanged) {
                        surfaceSizeChanged = false
                        needSizeChange = true
                        curW = surfaceWidth
                        curH = surfaceHeight
                    }
                }

                if (!isRunning) break

                if (needSizeChange && curW > 0 && curH > 0) {
                    renderer.onSurfaceChanged(null, curW, curH)
                }

                // Render frame
                try {
                    renderer.onDrawFrame(null)
                } catch (t: Throwable) {
                    Log.e("SoltiniGLTextureThread", "Uncaught error during onDrawFrame: ${t.message}", t)
                }

                // Swap buffers
                val eglInstance = egl
                val display = eglDisplay
                val surface = eglSurface
                if (eglInstance != null && display != null && surface != null && hasEglSurface) {
                    if (!eglInstance.eglSwapBuffers(display, surface)) {
                        val error = eglInstance.eglGetError()
                        if (error == 12299 || error == 12301) { // EGL_BAD_NATIVE_WINDOW or EGL_CONTEXT_LOST
                            synchronized(lock) {
                                destroySurface()
                            }
                        }
                    }
                }

                try {
                    sleep(frameDelayMs.toLong())
                } catch (ignored: InterruptedException) {}
            }

            destroySurface()
            destroyContext()
        }
    }
}
