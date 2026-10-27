package com.sixzero.tikskip

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var enableBtn: TextView
    private lateinit var count: TextView
    private lateinit var seenView: TextView
    private val tick = android.os.Handler(android.os.Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { updateStatus(); tick.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.load(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0E12"))
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "TIKSKIP"; textSize = 22f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.25f
        })
        root.addView(TextView(this).apply {
            text = "auto-swipes past the stuff you don't want on your For You page"
            textSize = 13f; setTextColor(Color.parseColor("#7A7A88")); setPadding(0, dp(4), 0, dp(20))
        })

        status = TextView(this).apply { textSize = 13f; setPadding(0, 0, 0, dp(10)) }
        root.addView(status)

        enableBtn = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.08f
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(enableBtn)

        root.addView(TextView(this).apply {
            text = "SKIP"; textSize = 11f; setTextColor(Color.parseColor("#7A7A88")); letterSpacing = 0.2f
            setPadding(0, dp(28), 0, dp(6))
        })

        fun toggle(label: String, sub: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor("#17171D")); setPadding(dp(16), dp(14), dp(12), dp(14))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply { text = label; textSize = 15f; setTextColor(Color.WHITE) })
            col.addView(TextView(this).apply { text = sub; textSize = 12f; setTextColor(Color.parseColor("#7A7A88")) })
            val sw = Switch(this).apply {
                isChecked = get()
                setOnCheckedChangeListener { _, v -> set(v); Prefs.save(this@MainActivity) }
            }
            row.addView(col); row.addView(sw); root.addView(row)
        }

        toggle("Skipper", "master switch — also in the notification", { Prefs.enabled }) { Prefs.enabled = it; Notif.show(this) }
        toggle("Ads", "sponsored / promoted / shop posts", { Prefs.ads }) { Prefs.ads = it }
        toggle("Lives", "livestreams in the feed", { Prefs.lives }) { Prefs.lives = it }
        toggle("Slideshows", "photo carousels (1/5 counter)", { Prefs.slideshows }) { Prefs.slideshows = it }
        toggle("Photo posts", "anything on screen that isn't a video (covers single images + carousels)", { Prefs.images }) { Prefs.images = it }

        root.addView(TextView(this).apply {
            text = "AUTO-SCROLL"; textSize = 11f; setTextColor(Color.parseColor("#7A7A88")); letterSpacing = 0.2f
            setPadding(0, dp(20), 0, dp(6))
        })
        toggle("Auto-scroll videos", "next video the moment the current one ends — toggle from the notification too", { Prefs.autoScroll }) { Prefs.autoScroll = it; Notif.show(this) }
        val autoLabel = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE) }
        val autoSeek = SeekBar(this).apply {
            max = 60; progress = Prefs.autoScrollSec
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                    Prefs.autoScrollSec = maxOf(3, p); autoLabel.text = "fallback: ${Prefs.autoScrollSec}s if the video has no progress bar"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { Prefs.save(this@MainActivity); Notif.show(this@MainActivity) }
            })
        }
        autoLabel.text = "fallback: ${Prefs.autoScrollSec}s if the video has no progress bar"
        root.addView(autoLabel); root.addView(autoSeek)

        root.addView(TextView(this).apply {
            text = "REACTION TIME"; textSize = 11f; setTextColor(Color.parseColor("#7A7A88")); letterSpacing = 0.2f
            setPadding(0, dp(20), 0, dp(6))
        })
        val delayLabel = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE) }
        val seek = SeekBar(this).apply {
            max = 1500; progress = Prefs.delayMs.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                    Prefs.delayMs = maxOf(100, p).toLong(); delayLabel.text = "${Prefs.delayMs} ms after a new video appears"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { Prefs.save(this@MainActivity) }
            })
        }
        delayLabel.text = "${Prefs.delayMs} ms after a new video appears"
        root.addView(delayLabel); root.addView(seek)
        root.addView(TextView(this).apply {
            text = "lower = faster skips but more chance of a false hit while the screen is still loading"
            textSize = 11f; setTextColor(Color.parseColor("#55555F")); setPadding(0, dp(4), 0, 0)
        })

        root.addView(TextView(this).apply {
            text = "EXTRA SKIP WORDS"; textSize = 11f; setTextColor(Color.parseColor("#7A7A88")); letterSpacing = 0.2f
            setPadding(0, dp(24), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "comma separated. If a post shows a word in the list below and you want it gone, type it here."
            textSize = 11f; setTextColor(Color.parseColor("#55555F")); setPadding(0, 0, 0, dp(6))
        })
        val custom = android.widget.EditText(this).apply {
            setText(Prefs.custom); hint = "e.g. shop, giveaway, tiktok shop"
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#55555F")); textSize = 13f
            setBackgroundColor(Color.parseColor("#17171D")); setPadding(dp(12), dp(12), dp(12), dp(12))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { Prefs.custom = e?.toString() ?: ""; Prefs.save(this@MainActivity) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(custom)

        count = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#7A7A88")); setPadding(0, dp(28), 0, 0) }
        root.addView(count)

        root.addView(TextView(this).apply {
            text = "WHAT THE SERVICE SEES RIGHT NOW"; textSize = 11f; setTextColor(Color.parseColor("#7A7A88")); letterSpacing = 0.2f
            setPadding(0, dp(24), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "open TikTok, come back here. If something wasn't skipped, screenshot this list — the label it should've caught is in it."
            textSize = 11f; setTextColor(Color.parseColor("#55555F")); setPadding(0, 0, 0, dp(8))
        })
        val copyBtn = TextView(this).apply {
            text = "COPY LOG OF LAST 20 SCREENS"; textSize = 13f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.08f
            setBackgroundColor(Color.parseColor("#5CE1E6")); setTextColor(Color.BLACK)
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            setOnClickListener {
                val sb = StringBuilder()
                synchronized(SkipService.history) {
                    for ((t, labels) in SkipService.history) {
                        sb.append("---- ").append(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(t))).append('\n')
                        labels.forEach { sb.append(it).append('\n') }
                    }
                }
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("tikskip log", sb.toString()))
                android.widget.Toast.makeText(this@MainActivity, "copied — paste it to Claude", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(copyBtn)
        seenView = TextView(this).apply {
            textSize = 12f; setTextColor(Color.parseColor("#B0B0BC")); typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#17171D")); setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(seenView)

        setContentView(ScrollView(this).apply { addView(root) })
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        tick.post(refresh)
    }

    override fun onPause() { tick.removeCallbacks(refresh); super.onPause() }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (SkipService.running) Notif.show(this)
    }

    private fun updateStatus() {
        Prefs.load(this)
        val on = SkipService.running
        status.text = if (on) "● service is ON" else "○ service is OFF — enable it in Accessibility settings"
        status.setTextColor(if (on) Color.parseColor("#7CFC9B") else Color.parseColor("#FF5C5C"))
        enableBtn.text = if (on) "OPEN ACCESSIBILITY SETTINGS" else "ENABLE TIKSKIP"
        enableBtn.setBackgroundColor(if (on) Color.parseColor("#22222A") else Color.parseColor("#5CE1E6"))
        enableBtn.setTextColor(if (on) Color.WHITE else Color.BLACK)
        count.text = "scans: ${SkipService.scans}  •  skipped: ${Prefs.skipped}" + (if (SkipService.lastReason.isNotEmpty()) "  •  last: ${SkipService.lastReason}" else "")
        val seen = SkipService.lastSeen
        seenView.text = if (seen.isEmpty()) "(nothing yet — service hasn't scanned TikTok)" else seen.joinToString("\n")
    }
}
