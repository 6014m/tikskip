package com.sixzero.tikskip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches the TikTok screen. When the current post's labels say it's an ad,
 * a live, a photo slideshow or a still image, swipe up to the next one.
 *
 * Approach borrowed from havanphu03032007-cmd/TikTok-Ad-Skipper: scan the node
 * tree on a fixed interval (not debounced — TikTok never stops firing events),
 * exact-match short labels, ignore the LIVE tab in the top bar.
 */
class SkipService : AccessibilityService() {

    companion object {
        const val TAG = "TikSkip"
        @Volatile var running = false
        @Volatile var lastReason = ""
        /** Last short strings seen on screen — shown in the app for tuning. */
        @Volatile var lastSeen: List<String> = emptyList()
        /** Rolling history of distinct label sets, newest last. */
        val history = ArrayDeque<Pair<Long, List<String>>>()
        @Volatile var scans = 0

        private val TIKTOK = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go")

        // exact match, lowercase, trimmed
        private val AD_EXACT = setOf("sponsored", "ad", "ads", "promoted", "paid partnership", "advertisement", "promotion", "sponsored content")
        // contained anywhere in a short label (<= 40 chars)
        private val AD_CONTAINS = listOf("shop now", "learn more", "download now", "install now", "play now", "sign up", "order now", "book now", "get offer", "apply now", "visit site")
        private val LIVE_EXACT = setOf("live", "live now", "watch live", "en vivo")
        private val SLIDE_EXACT = setOf("swipe", "photo mode", "slideshow", "swipe for more")
        private val SLIDE_COUNTER = Regex("^\\d{1,2}\\s*/\\s*\\d{1,2}$")
        private val SLIDE_IDS = listOf("photo", "slide", "carousel", "image_viewer", "indicator", "dot")
        private val IMAGE_IDS = listOf("photo", "image", "picture")
        private val VIDEO_IDS = listOf("video", "surface", "player", "texture")
        private val COMMENT_SCREEN = listOf("add comment", "reply to", "add a comment", "post comment", "say something", "view replies", "view more replies", "most liked", "most relevant", "newest", "be the first to comment", "no comments yet", "sort comments")
        private val COMMENT_IDS = listOf("comment_list", "comment_input", "comment_edit", "comment_container", "comment_sheet", "commentlist", "reply_")

        private const val COOLDOWN_MS = 900L
        private const val SCAN_MS = 150L
        private const val TOP_BAR = 0.15f
        private const val MAX_DEPTH = 45
    }

    private var lastSwipe = 0L
    private var lastSkipSig = ""
    private var retries = 0
    private var curSig = ""
    private var curSince = 0L
    private var lastProgress = -1f
    private var maxProgress = -1f
    private var sawProgress = false
    private var lastScan = 0L
    private var screenH = 0
    private var screenW = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.load(this)
        running = true
        Notif.show(this)
        Log.i(TAG, "connected")
    }

    override fun onDestroy() { running = false; Notif.hide(this); super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() !in TIKTOK) return
        val now = System.currentTimeMillis()
        if (now - lastSwipe < COOLDOWN_MS) return
        if (now - lastScan < SCAN_MS) return
        lastScan = now

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() !in TIKTOK) return
        screenH = resources.displayMetrics.heightPixels
        screenW = resources.displayMetrics.widthPixels

        Prefs.load(this)
        val all = ArrayList<Seen>()
        collect(root, all, 0)
        // TikTok's tree includes the previous/next posts too; only judge what's actually on screen
        val seen = all.filter { it.onScreen }
        val labels = seen.map { if (it.text.isNotEmpty()) it.text else "#" + (it.id ?: "") }.distinct()
        lastSeen = labels.filter { it.length <= 40 }.take(60)
        scans++
        synchronized(history) {
            if (history.lastOrNull()?.second != labels) {
                history.addLast(now to labels)
                while (history.size > 20) history.removeFirst()
            }
        }

        val lowered = seen.map { it.copy(text = it.text.lowercase().trim()) }
        val ids = lowered.mapNotNull { it.id }.joinToString(" ")
        val texts0 = lowered.map { it.text }
        // comment sheet / reply box open: leave it alone
        // "Read or add comments. N comments" is on EVERY post — must not count as the comment sheet
        // Comment sheet is its own screen: no "<user> profile" labels, and it has Reply / "View N replies" / Thumbs up.
        // NOTE "Read or add comments. N comments" is on EVERY post and must never count.
        val onFeedPost = texts0.any { it.endsWith(" profile") }
        val commentsOpen = !onFeedPost && texts0.any { t ->
            t == "reply" || t == "thumbs up" || t.endsWith(" replies") || t.endsWith(" reply") ||
            t.startsWith("add comment") || t.startsWith("add a comment") || t.startsWith("reply to ") || t.startsWith("say something")
        }
        if (commentsOpen) { lastSkipSig = ""; retries = 0; return }

        val customWords = Prefs.custom.split(",", "\n").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val texts = lowered.map { it.text }
        val hasActions = texts.any { it.startsWith("like ") || it.startsWith("share ") || it.contains("comments") }
        val hasVideoTag = texts.any { it == "video" || it.startsWith("like video") || it.startsWith("share video") }
        val hasPhotoTag = texts.any { it == "photo" || it == "photos" || it == "image" || it.startsWith("like photo") || it.startsWith("share photo") || it.startsWith("photo ") }
        val promotedSound = texts.any { it.startsWith("sound: promoted music") || it.contains("promoted music") || it.contains("promotional music") }
        val slideCounter = texts.any { SLIDE_COUNTER.matches(it) || it in SLIDE_EXACT } || SLIDE_IDS.any { ids.contains(it) }

        val sig = texts.firstOrNull { it.endsWith(" profile") } ?: ""
        if (sig != curSig) { curSig = sig; curSince = now; lastProgress = -1f; maxProgress = -1f; sawProgress = false }

        // video end detection: progress bar on the current post wraps from near the end back to the start
        val prog = seen.filter { it.progress >= 0f && !it.topBar }.maxByOrNull { it.progress }?.progress ?: -1f
        var videoEnded = false
        if (prog >= 0f) {
            sawProgress = true
            if (prog > maxProgress) maxProgress = prog
            if (lastProgress >= 0.7f && prog < 0.2f) videoEnded = true   // looped
            if (prog >= 0.985f) videoEnded = true                        // hit the end
            lastProgress = prog
        }
        val timerFallback = !sawProgress && now - curSince >= Prefs.autoScrollSec * 1000L

        val reason = when {
            !Prefs.enabled && !Prefs.autoScroll -> null
            Prefs.autoScroll && sig.isNotEmpty() && (videoEnded || timerFallback) -> "auto"
            !Prefs.enabled -> null
            customWords.isNotEmpty() && lowered.any { s -> s.text.length <= 60 && customWords.any { s.text.contains(it) } } -> "custom"
            Prefs.ads && (promotedSound || lowered.any { it.text in AD_EXACT || (it.text.length <= 40 && AD_CONTAINS.any { w -> it.text.contains(w) }) }) -> "ad"
            Prefs.lives && lowered.any { it.text in LIVE_EXACT && !it.topBar } -> "live"
            Prefs.slideshows && hasActions && (slideCounter || (!hasVideoTag && hasPhotoTag)) -> "slideshow"
            Prefs.images && hasActions && !hasVideoTag -> "image"
            else -> null
        } ?: run { lastSkipSig = ""; retries = 0; return }

        // same post still on screen after we swiped: TikTok ate the gesture, try again (max 3)
        if (reason == "auto") { curSince = now } else if (sig.isNotEmpty() && sig == lastSkipSig) {
            if (retries >= 3) return
            retries++
        } else { retries = 0 }
        lastSkipSig = sig

        lastSwipe = now
        lastReason = reason
        synchronized(history) { history.addLast(now to listOf(">>> SKIP ($reason) retry=$retries on $sig")) }
        Prefs.skipped++; Prefs.save(this)
        Log.i(TAG, "skip: $reason")
        try { (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator).vibrate(android.os.VibrationEffect.createOneShot(40, 120)) } catch (_: Exception) {}
        swipeUp()
    }

    private data class Seen(val text: String, val id: String?, val topBar: Boolean, val onScreen: Boolean, val progress: Float = -1f)

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<Seen>, depth: Int) {
        if (n == null || depth > MAX_DEPTH) return
        val id = n.viewIdResourceName?.substringAfter('/')?.lowercase()
        val r = Rect(); n.getBoundsInScreen(r)
        val top = r.top < screenH * TOP_BAR
        // a node counts as on screen if most of it is inside the display
        val on = r.bottom > screenH * 0.05f && r.top < screenH * 0.95f && r.right > 0 && r.left < screenW
        n.text?.toString()?.let { if (it.isNotBlank()) out.add(Seen(it, id, top, on)) }
        n.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(Seen(it, id, top, on)) }
        if (id != null && n.text.isNullOrBlank() && n.contentDescription.isNullOrBlank()) out.add(Seen("", id, top, on))
        // video progress bar: anything with a range (SeekBar / ProgressBar) — current/max as 0..1
        n.rangeInfo?.let { ri ->
            val span = ri.max - ri.min
            if (span > 0f) out.add(Seen("~progress ${"%.2f".format((ri.current - ri.min) / span)} ${n.className?.toString()?.substringAfterLast('.') ?: ""} #${id ?: ""}", id, top, on, (ri.current - ri.min) / span))
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }

    private fun swipeUp() {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.5f
        // fast flick: short duration = high velocity = TikTok snaps to the next post instantly
        val path = Path().apply { moveTo(x, dm.heightPixels * 0.70f); lineTo(x, dm.heightPixels * 0.30f) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gd: GestureDescription?) { note(">>> gesture completed") }
            override fun onCancelled(gd: GestureDescription?) { note(">>> gesture CANCELLED by system") }
        }, null)
        if (!ok) note(">>> dispatchGesture returned false")
    }

    private fun note(msg: String) {
        synchronized(history) { history.addLast(System.currentTimeMillis() to listOf(msg)); while (history.size > 20) history.removeFirst() }
    }
}
