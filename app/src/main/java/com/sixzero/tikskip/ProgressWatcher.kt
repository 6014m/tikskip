package com.sixzero.tikskip

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import java.util.concurrent.Executor

/**
 * Reads TikTok's thin progress line (bottom of the video) from screenshots.
 * Reports progress 0..1, or -1 when no line is found. Android 11+.
 */
class ProgressWatcher(private val svc: AccessibilityService, private val onProgress: (Float) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val exec = Executor { r -> r.run() }
    @Volatile var active = false
    private var busy = false
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            if (!busy) shoot()
            handler.postDelayed(this, 350)
        }
    }

    fun start() { if (Build.VERSION.SDK_INT < 30) return; if (active) return; active = true; handler.post(tick) }
    fun stop() { active = false; handler.removeCallbacks(tick) }

    private fun shoot() {
        if (Build.VERSION.SDK_INT < 30) return
        busy = true
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    try {
                        val hb = r.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                        hb.close()
                        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        if (bmp != null) { onProgress(measure(bmp)); bmp.recycle() }
                    } catch (_: Exception) {} finally { busy = false }
                }
                override fun onFailure(code: Int) { busy = false }
            })
        } catch (_: Exception) { busy = false }
    }

    /**
     * The progress line: starts at x=0, bright (played part), followed by a dim gray track.
     * Scan rows in the bottom ~20% of the screen, pick the row that best matches, return played fraction.
     */
    private fun measure(b: Bitmap): Float {
        val w = b.width; val h = b.height
        val y0 = (h * 0.78f).toInt(); val y1 = (h * 0.97f).toInt()
        val row = IntArray(w)
        var best = -1f; var bestScore = 0
        for (y in y0 until y1) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            // must start bright at the left edge
            if (lum(row[2]) < 150) continue
            var x = 0; var gaps = 0
            while (x < w) {
                if (lum(row[x]) >= 150) { gaps = 0 } else { gaps++; if (gaps > 3) break }
                x++
            }
            val end = x - gaps
            // after the bright run, the rest of the row should be the dim track (or dark), not more bright video
            var dim = 0; var samples = 0
            var xx = end + 6
            while (xx < w) { val l = lum(row[xx]); if (l < 140) dim++; samples++; xx += 8 }
            val restDim = samples == 0 || dim.toFloat() / samples > 0.9f
            if (!restDim) continue
            // reject rows that are bright edge-to-edge AND flanked by bright rows (that's content, not a 2px line)
            val score = end
            if (score > bestScore) { bestScore = score; best = end.toFloat() / w }
        }
        return best
    }

    private fun lum(c: Int): Int = ((c shr 16 and 0xff) * 299 + (c shr 8 and 0xff) * 587 + (c and 0xff) * 114) / 1000
}
