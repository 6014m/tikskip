package com.sixzero.tikskip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches the TikTok screen. When the current video's labels say it's an ad,
 * a live, a photo slideshow or a still image, swipe up to the next one.
 */
class SkipService : AccessibilityService() {

    companion object {
        const val TAG = "TikSkip"
        @Volatile var running = false
        @Volatile var lastReason = ""
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingCheck: Runnable? = null
    private var lastSwipe = 0L
    private var lastFingerprint = ""

    // Text that marks each kind of post. Case-insensitive. Add to these if TikTok renames things.
    private val adWords = listOf("sponsored", "promoted", "paid partnership", "advertisement", "shop now", "learn more", "download now", "install now", "tiktok shop", "promotion")
    private val liveWords = listOf("live", "live now", "is live", "watching", "livestream")
    private val slideshowWords = listOf("swipe", "photo mode", "/ ", "slideshow", "tap to see")
    private val slideshowIdHints = listOf("photo", "slide", "carousel", "image_viewer", "indicator")
    private val imageIdHints = listOf("photo", "image")

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.load(this)
        running = true
        Log.i(TAG, "connected")
    }

    override fun onDestroy() { running = false; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // debounce: screens change constantly while scrolling; look once it settles
        pendingCheck?.let { handler.removeCallbacks(it) }
        val r = Runnable { check() }
        pendingCheck = r
        handler.postDelayed(r, Prefs.delayMs)
    }

    private fun check() {
        val root = rootInActiveWindow ?: return
        try {
            val texts = ArrayList<String>()
            val ids = ArrayList<String>()
            collect(root, texts, ids, 0)
            val joined = texts.joinToString(" | ").lowercase()
            val idJoined = ids.joinToString(" | ").lowercase()

            // skip re-evaluating the exact same screen (prevents double swipes)
            val fp = joined.hashCode().toString() + idJoined.hashCode()
            if (fp == lastFingerprint) return
            lastFingerprint = fp

            // Only act on the main feed: comments / profile / search pages contain lots of these words legitimately.
            if (!looksLikeFeed(texts, idJoined)) return

            val reason = when {
                Prefs.ads && adWords.any { containsWord(joined, it) } -> "ad"
                Prefs.lives && isLive(texts, idJoined) -> "live"
                Prefs.slideshows && isSlideshow(joined, idJoined) -> "slideshow"
                Prefs.images && isStillImage(joined, idJoined) -> "image"
                else -> null
            } ?: return

            val now = System.currentTimeMillis()
            if (now - lastSwipe < 900) return
            lastSwipe = now
            lastReason = reason
            Prefs.skipped++; Prefs.save(this)
            Log.i(TAG, "skip: $reason")
            swipeUp()
        } finally {
            root.recycle()
        }
    }

    private fun looksLikeFeed(texts: List<String>, ids: String): Boolean {
        // The feed shows like/comment/share counters and a "For You" / "Following" header.
        val t = texts.joinToString(" ").lowercase()
        val hasTabs = t.contains("for you") || t.contains("following") || t.contains("friends")
        val hasActions = ids.contains("like") || ids.contains("comment") || ids.contains("share") || t.contains("comments")
        val onComments = t.contains("add comment") || t.contains("reply to")
        return hasTabs && hasActions && !onComments
    }

    private fun isLive(texts: List<String>, ids: String): Boolean {
        // "LIVE" badge is usually its own short node; avoid matching "I'm live in Tokyo" captions
        val badge = texts.any { s -> val l = s.trim().lowercase(); l == "live" || l == "live now" || l.endsWith(" watching") }
        return badge || ids.contains("live_") || ids.contains("_live")
    }

    private fun isSlideshow(joined: String, ids: String): Boolean {
        // photo carousels show a "1/5"-style counter and dots indicator
        val counter = Regex("\\b\\d{1,2}\\s*/\\s*\\d{1,2}\\b").containsMatchIn(joined)
        return counter || slideshowIdHints.any { ids.contains(it) } || joined.contains("photo mode")
    }

    private fun isStillImage(joined: String, ids: String): Boolean {
        // single photo posts: image viewer with no video surface
        val hasImage = imageIdHints.any { ids.contains(it) }
        val hasVideo = ids.contains("video") || ids.contains("surface") || ids.contains("player")
        return hasImage && !hasVideo
    }

    private fun containsWord(hay: String, word: String): Boolean =
        Regex("(^|[^a-z])" + Regex.escape(word) + "($|[^a-z])").containsMatchIn(hay)

    private fun collect(n: AccessibilityNodeInfo?, texts: MutableList<String>, ids: MutableList<String>, depth: Int) {
        if (n == null || depth > 40) return
        n.text?.let { if (it.isNotBlank()) texts.add(it.toString()) }
        n.contentDescription?.let { if (it.isNotBlank()) texts.add(it.toString()) }
        n.viewIdResourceName?.let { ids.add(it.substringAfter('/')) }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            collect(c, texts, ids, depth + 1)
            c.recycle()
        }
    }

    private fun swipeUp() {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.5f
        val y1 = dm.heightPixels * 0.72f
        val y2 = dm.heightPixels * 0.28f
        val path = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 140))
            .build()
        dispatchGesture(g, null, null)
    }
}
