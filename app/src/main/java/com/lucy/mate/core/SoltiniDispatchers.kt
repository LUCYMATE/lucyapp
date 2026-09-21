package com.lucy.mate.core

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dedicated CoroutineDispatchers for Soltini low-latency architecture.
 *
 * - VoiceAudioDispatcher: Isolated SingleThreadExecutor with Process.THREAD_PRIORITY_URGENT_AUDIO
 *   for real-time hardware microphone recording and AudioTrack playback.
 * - LiveWsDispatcher: Isolated SingleThreadExecutor for WebSocket reading and non-blocking sending.
 * - AgentWorkerDispatcher: Dedicated thread pool (Executors.newFixedThreadPool(4)) for
 *   background tool execution, vision, and accessibility so heavy work never starves audio or WebSocket.
 */
object SoltiniDispatchers {

    /**
     * Dedicated single thread with THREAD_PRIORITY_URGENT_AUDIO exclusively for hardware microphone capture.
     */
    val MicCaptureDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor(
        object : ThreadFactory {
            override fun newThread(r: Runnable): Thread {
                return Thread {
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    } catch (_: Throwable) {}
                    r.run()
                }.apply {
                    name = "Soltini-MicCapture"
                    isDaemon = true
                }
            }
        }
    ).asCoroutineDispatcher()

    /**
     * Dedicated single thread with THREAD_PRIORITY_URGENT_AUDIO exclusively for hardware AudioTrack playback.
     */
    val AudioPlaybackDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor(
        object : ThreadFactory {
            override fun newThread(r: Runnable): Thread {
                return Thread {
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    } catch (_: Throwable) {}
                    r.run()
                }.apply {
                    name = "Soltini-AudioPlayback"
                    isDaemon = true
                }
            }
        }
    ).asCoroutineDispatcher()

    /**
     * Backward-compatible alias pointing to AudioPlaybackDispatcher.
     */
    val VoiceAudioDispatcher: CoroutineDispatcher = AudioPlaybackDispatcher

    /**
     * Dedicated single thread for WebSocket reading and non-blocking sending.
     */
    val LiveWsDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor(
        object : ThreadFactory {
            override fun newThread(r: Runnable): Thread {
                return Thread(r).apply {
                    name = "Soltini-LiveWs"
                    isDaemon = true
                }
            }
        }
    ).asCoroutineDispatcher()

    /**
     * Dedicated fixed thread pool (4 threads) for background tool execution, vision, and accessibility.
     */
    val AgentWorkerDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(
        4,
        object : ThreadFactory {
            private val counter = AtomicInteger(1)
            override fun newThread(r: Runnable): Thread {
                return Thread(r).apply {
                    name = "Soltini-AgentWorker-${counter.getAndIncrement()}"
                    isDaemon = true
                }
            }
        }
    ).asCoroutineDispatcher()

    /**
     * Dedicated single thread with THREAD_PRIORITY_BACKGROUND exclusively for loading,
     * reading, and parsing 3D/2D model assets from disk/assets without competing with UI or Audio.
     */
    val ModelLoaderDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor(
        object : ThreadFactory {
            override fun newThread(r: Runnable): Thread {
                return Thread {
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    } catch (_: Throwable) {}
                    r.run()
                }.apply {
                    name = "Soltini-ModelLoader"
                    isDaemon = true
                }
            }
        }
    ).asCoroutineDispatcher()
}
