package com.soltini.app.agent

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import org.json.JSONObject

/**
 * YouTubeAutomator
 *
 * Dedicated automation layer for YouTube. Combines:
 *  1. Intent-based deep links (fastest — no UI scraping needed for search/open)
 *  2. Media key dispatch (play/pause, next, previous — works even in background)
 *  3. AccessibilityService node clicking (like, subscribe, skip ad, quality)
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  TOOL               │ TRIGGER PHRASE                                    │
 * │─────────────────────┼───────────────────────────────────────────────────│
 * │  youtube_search     │ "search YouTube for X" / "play X on YouTube"     │
 * │  youtube_open_video │ "open the first video" / "play that video"        │
 * │  youtube_play_pause │ "pause" / "resume" / "play" / "pause YouTube"    │
 * │  youtube_seek       │ "skip forward 30 seconds" / "rewind 10 seconds"  │
 * │  youtube_next       │ "next video" / "skip video"                      │
 * │  youtube_previous   │ "previous video" / "go back"                     │
 * │  youtube_like       │ "like this video" / "give a thumbs up"           │
 * │  youtube_dislike    │ "dislike" / "thumbs down"                        │
 * │  youtube_subscribe  │ "subscribe to this channel"                      │
 * │  youtube_skip_ad    │ "skip the ad" / "skip ad"                        │
 * │  youtube_set_quality│ "change quality to 1080p" / "set to 720p"        │
 * │  youtube_fullscreen │ "go fullscreen" / "exit fullscreen"              │
 * │  youtube_mute       │ "mute YouTube"                                   │
 * │  youtube_captions   │ "turn on captions" / "show subtitles"            │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * NOTE: youtube_search and most intents work WITHOUT accessibility.
 *       Node-based actions (like, subscribe, skip ad, quality) require
 *       the AccessibilityService to be active.
 */
class YouTubeAutomator(private val context: Context) {

    companion object {
        private const val TAG = "YouTubeAutomator"
        private const val YOUTUBE_PKG = "com.google.android.youtube"

        // YouTube accessibility content descriptions (English — shipped with app)
        private val LIKE_LABELS = listOf("like", "thumb up", "thumbs up", "Like this video")
        private val DISLIKE_LABELS = listOf("dislike", "thumb down", "thumbs down", "Dislike this video")
        private val SUBSCRIBE_LABELS = listOf("subscribe", "Subscribe")
        private val SKIP_AD_LABELS = listOf("Skip Ad", "Skip Ads", "SKIP ADS", "Skip ad", "skip")
        private val FULLSCREEN_LABELS = listOf("Full screen", "Fullscreen", "Exit full screen", "full screen")
        private val CAPTIONS_LABELS = listOf("Closed captions", "Captions", "CC", "Subtitles")
        private val QUALITY_MENU_LABELS = listOf("More", "overflow", "Settings", "Quality")
        private val QUALITY_OPTION_LABELS = listOf("Quality")
    }

    // ─── Search / Open ────────────────────────────────────────────────────────

    /**
     * Opens YouTube and performs a search for [query].
     * Uses a deep link Intent — no accessibility needed.
     */
    fun search(query: String): JSONObject {
        if (query.isBlank()) return error("Search query is required")
        return try {
            // Method 1: YouTube search deep link (most reliable)
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage(YOUTUBE_PKG)
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "YouTube search: $query")
            result("status", "searching", "query", query)
        } catch (e: Exception) {
            // Method 2: Fallback via web URL
            try {
                val encodedQuery = Uri.encode(query)
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=$encodedQuery")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(webIntent)
                result("status", "searching_web_fallback", "query", query)
            } catch (e2: Exception) {
                error("Could not open YouTube: ${e2.message}")
            }
        }
    }

    /**
     * Opens a YouTube video by URL or video ID.
     */
    fun openVideo(videoIdOrUrl: String): JSONObject {
        if (videoIdOrUrl.isBlank()) return error("video_id or url is required")
        return try {
            val url = if (videoIdOrUrl.startsWith("http")) {
                videoIdOrUrl
            } else {
                "https://www.youtube.com/watch?v=$videoIdOrUrl"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(YOUTUBE_PKG)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            result("status", "opening_video", "url", url)
        } catch (e: Exception) {
            error("Failed to open video: ${e.message}")
        }
    }

    // ─── Playback Control (Media Keys) ────────────────────────────────────────

    /**
     * Toggles play/pause on YouTube (or any active media session).
     * Media key dispatch works even when YouTube is in the background.
     */
    fun playPause(): JSONObject {
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        return result("status", "play_pause_toggled")
    }

    /**
     * Skips to the next video / track in the queue.
     */
    fun nextVideo(): JSONObject {
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        return result("status", "next_video")
    }

    /**
     * Goes back to the previous video / track.
     */
    fun previousVideo(): JSONObject {
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        return result("status", "previous_video")
    }

    /**
     * Seeks forward by tapping the fast-forward region (double-tap right side of video).
     * For custom durations, we use the +10s / -10s button via accessibility.
     *
     * @param seconds how many seconds to seek (positive = forward, negative = backward)
     */
    fun seek(seconds: Int): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")

        val taps = Math.abs(seconds) / 10  // Each tap = ~10 seconds
        val forward = seconds > 0

        if (taps == 0) return error("Seek amount must be at least 10 seconds")

        // Approximate center of the screen
        val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
        val screenHeight = context.resources.displayMetrics.heightPixels.toFloat()

        // Double-tap the right 1/3 of screen to skip forward, left 1/3 to rewind
        val tapX = if (forward) screenWidth * 0.75f else screenWidth * 0.25f
        val tapY = screenHeight * 0.5f

        repeat(taps.coerceAtMost(5)) { // Max 5 taps (50s)
            svc.tapAt(tapX, tapY)
            Thread.sleep(150)
            svc.tapAt(tapX, tapY) // Double-tap
            Thread.sleep(200)
        }

        val direction = if (forward) "forward" else "backward"
        return result("status", "seeked_$direction", "seconds", seconds.toString())
    }

    // ─── UI Actions (Accessibility Required) ─────────────────────────────────

    /**
     * Likes the current video by finding and clicking the Like button.
     */
    fun likeVideo(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        return clickOneOf(svc, LIKE_LABELS, "Like button")
    }

    /**
     * Dislikes the current video.
     */
    fun dislikeVideo(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        return clickOneOf(svc, DISLIKE_LABELS, "Dislike button")
    }

    /**
     * Subscribes to the channel of the currently playing video.
     */
    fun subscribe(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        return clickOneOf(svc, SUBSCRIBE_LABELS, "Subscribe button")
    }

    /**
     * Clicks the "Skip Ad" button if a skippable ad is playing.
     */
    fun skipAd(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")

        // Try up to 3 times (ad skip button sometimes appears with a delay)
        repeat(3) {
            for (label in SKIP_AD_LABELS) {
                if (svc.clickByText(label)) {
                    return result("status", "ad_skipped")
                }
            }
            Thread.sleep(500)
        }
        return error("No skippable ad found — the ad may not be skippable yet or there is no ad playing")
    }

    /**
     * Opens the quality settings menu and selects the requested quality.
     * @param quality e.g. "1080p", "720p", "480p", "Auto"
     */
    fun setQuality(quality: String): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")

        // First, tap the video to show controls
        val screen = context.resources.displayMetrics
        svc.tapAt(screen.widthPixels / 2f, screen.heightPixels / 2f)
        Thread.sleep(400)

        // Click the overflow / more options button
        var opened = false
        for (label in QUALITY_MENU_LABELS) {
            if (svc.clickByText(label)) { opened = true; break }
        }
        if (!opened) return error("Could not open YouTube settings menu — tap the video first to show controls")
        Thread.sleep(500)

        // Click "Quality" option in the menu
        if (!svc.clickByText("Quality")) {
            return error("Quality option not found in menu")
        }
        Thread.sleep(400)

        // Click the requested quality
        if (svc.clickByText(quality)) {
            return result("status", "quality_set", "quality", quality)
        }

        // Try partial match (e.g. user says "1080" and button says "1080p")
        val screen2 = svc.readScreen()
        val availableQualities = listOf("Auto", "144p", "240p", "360p", "480p", "720p", "1080p", "1440p", "2160p")
        val match = availableQualities.firstOrNull { it.contains(quality, ignoreCase = true) }
        if (match != null && svc.clickByText(match)) {
            return result("status", "quality_set", "quality", match)
        }

        return error("Quality '$quality' not found. Available: ${availableQualities.joinToString(", ")}")
    }

    /**
     * Toggles fullscreen mode by clicking the fullscreen button.
     */
    fun toggleFullscreen(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")

        // Show player controls first
        val screen = context.resources.displayMetrics
        svc.tapAt(screen.widthPixels / 2f, screen.heightPixels / 2f)
        Thread.sleep(300)

        return clickOneOf(svc, FULLSCREEN_LABELS, "Fullscreen button")
    }

    /**
     * Toggles closed captions / subtitles.
     */
    fun toggleCaptions(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")

        // Show player controls first
        val screen = context.resources.displayMetrics
        svc.tapAt(screen.widthPixels / 2f, screen.heightPixels / 2f)
        Thread.sleep(300)

        return clickOneOf(svc, CAPTIONS_LABELS, "Captions button")
    }

    /**
     * Mutes / unmutes using the device volume key media intent.
     */
    fun toggleMute(): JSONObject {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val isMuted = am.isStreamMute(AudioManager.STREAM_MUSIC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (isMuted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE,
                0
            )
        }
        val newState = if (isMuted) "unmuted" else "muted"
        return result("status", newState)
    }

    /**
     * Reads the current screen to find video title, channel, and other metadata
     * visible on the YouTube player or home screen.
     */
    fun readYouTubeScreen(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        val content = svc.readScreen()
        return JSONObject().apply {
            put("status", "ok")
            put("youtube_screen", content)
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Dispatches a media key press DOWN + UP to the AudioManager.
     * Works across all media apps including YouTube background playback.
     */
    private fun dispatchMediaKey(keyCode: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val eventTime = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
        Log.i(TAG, "Dispatched media key: $keyCode")
    }

    private fun clickOneOf(
        svc: SoltiniAccessibilityService,
        labels: List<String>,
        elementName: String
    ): JSONObject {
        for (label in labels) {
            if (svc.clickByText(label)) {
                Log.i(TAG, "Clicked: $label")
                return result("status", "clicked", "element", elementName)
            }
        }
        return error("$elementName not found on screen — make sure YouTube is open and the video is playing")
    }

    private fun result(vararg pairs: Any): JSONObject = JSONObject().apply {
        var i = 0
        while (i < pairs.size - 1) {
            put(pairs[i].toString(), pairs[i + 1])
            i += 2
        }
    }

    private fun error(msg: String): JSONObject = JSONObject().apply { put("error", msg) }
}
