package com.sixzero.tikskip

import android.content.Context

object Prefs {
    private const val NAME = "tikskip"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var ads = true; var lives = true; var slideshows = false; var images = false
    var delayMs = 350L
    var skipped = 0

    fun load(ctx: Context) {
        val p = sp(ctx)
        ads = p.getBoolean("ads", true)
        lives = p.getBoolean("lives", true)
        slideshows = p.getBoolean("slideshows", false)
        images = p.getBoolean("images", false)
        delayMs = p.getLong("delay", 350L)
        skipped = p.getInt("skipped", 0)
    }

    fun save(ctx: Context) {
        sp(ctx).edit()
            .putBoolean("ads", ads).putBoolean("lives", lives)
            .putBoolean("slideshows", slideshows).putBoolean("images", images)
            .putLong("delay", delayMs).putInt("skipped", skipped)
            .apply()
    }
}
