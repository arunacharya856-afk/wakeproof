package com.dixit.wakeproof

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** All settings and in-progress alarm state, saved so nothing is lost if the app restarts. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("wakeproof", Context.MODE_PRIVATE)

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).commit()
    }

    // ---- Settings ----
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = edit { putBoolean("enabled", v) }
    var hour: Int
        get() = sp.getInt("hour", 7)
        set(v) = edit { putInt("hour", v) }
    var minute: Int
        get() = sp.getInt("minute", 0)
        set(v) = edit { putInt("minute", v) }

    /** Calendar.DAY_OF_WEEK values (1 = Sunday ... 7 = Saturday). */
    var days: Set<Int>
        get() = (sp.getString("days", "1,2,3,4,5,6,7") ?: "")
            .split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        set(v) = edit { putString("days", v.sorted().joinToString(",")) }

    var pushups: Int
        get() = sp.getInt("pushups", 20)
        set(v) = edit { putInt("pushups", v) }
    var limitMin: Int
        get() = sp.getInt("limitMin", 10)
        set(v) = edit { putInt("limitMin", v) }
    var bedMin: Int
        get() = sp.getInt("bedMin", 3)
        set(v) = edit { putInt("bedMin", v) }
    var idleSec: Int
        get() = sp.getInt("idleSec", 60)
        set(v) = edit { putInt("idleSec", v) }
    var vibrateOnly: Boolean
        get() = sp.getBoolean("vibrateOnly", false)
        set(v) = edit { putBoolean("vibrateOnly", v) }
    var rampUp: Boolean
        get() = sp.getBoolean("rampUp", true)
        set(v) = edit { putBoolean("rampUp", v) }
    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = edit { putString("apiKey", v) }

    // ---- Desk check (Anki) ----
    var studyEnabled: Boolean
        get() = sp.getBoolean("studyEnabled", false)
        set(v) = edit { putBoolean("studyEnabled", v) }
    /** Desk check rings this many minutes after the wake-up alarm time. */
    var studyOffsetMin: Int
        get() = sp.getInt("studyOffsetMin", 30)
        set(v) = edit { putInt("studyOffsetMin", v) }
    private val studyTotalMin: Int get() = (hour * 60 + minute + studyOffsetMin) % (24 * 60)
    val studyHour: Int get() = studyTotalMin / 60
    val studyMinute: Int get() = studyTotalMin % 60
    var studyCards: Int
        get() = sp.getInt("studyCards", 20)
        set(v) = edit { putInt("studyCards", v) }
    var studyQuietMin: Int
        get() = sp.getInt("studyQuietMin", 20)
        set(v) = edit { putInt("studyQuietMin", v) }
    /** Desk check fired while the wake-up routine was still going; start it right after. */
    var studyQueued: Boolean
        get() = sp.getBoolean("studyQueued", false)
        set(v) = edit { putBoolean("studyQueued", v) }
    var studyStreak: Int
        get() = sp.getInt("studyStreak", 0)
        set(v) = edit { putInt("studyStreak", v) }
    var studyLastDone: String
        get() = sp.getString("studyLastDone", "") ?: ""
        set(v) = edit { putString("studyLastDone", v) }

    /** Which alarm is ringing: MODE_WAKE or MODE_STUDY. */
    var mode: Int
        get() = sp.getInt("mode", MODE_WAKE)
        set(v) = edit { putInt("mode", v) }

    val studyMs: Long get() = if (testMode) 2 * 60_000L else studyQuietMin * 60_000L

    // ---- Tired → library ----
    var libMin: Int
        get() = sp.getInt("libMin", 20)
        set(v) = edit { putInt("libMin", v) }
    val libMs: Long get() = libMin * 60_000L

    // ---- Stats ----
    var streak: Int
        get() = sp.getInt("streak", 0)
        set(v) = edit { putInt("streak", v) }
    var lastDone: String
        get() = sp.getString("lastDone", "") ?: ""
        set(v) = edit { putString("lastDone", v) }

    // ---- Live alarm state ----
    var ringing: Boolean
        get() = sp.getBoolean("ringing", false)
        set(v) = edit {
            putBoolean("ringing", v)
            if (v && !sp.getBoolean("ringing", false)) putLong("ringSince", System.currentTimeMillis())
        }
    /** When the current alarm started ringing (the phone lock releases after an hour as a safety net). */
    val ringSince: Long get() = sp.getLong("ringSince", 0L)
    /** The camera app may be used until this time (during a photo step). */
    var cameraUntil: Long
        get() = sp.getLong("cameraUntil", 0L)
        set(v) = edit { putLong("cameraUntil", v) }
    var phase: Int
        get() = sp.getInt("phase", 0)
        set(v) = edit { putInt("phase", v) }
    var reps: Int
        get() = sp.getInt("reps", 0)
        set(v) = edit { putInt("reps", v) }
    var startedAt: Long
        get() = sp.getLong("startedAt", 0L)
        set(v) = edit { putLong("startedAt", v) }
    var bedStartedAt: Long
        get() = sp.getLong("bedStartedAt", 0L)
        set(v) = edit { putLong("bedStartedAt", v) }
    var testMode: Boolean
        get() = sp.getBoolean("testMode", false)
        set(v) = edit { putBoolean("testMode", v) }
    var photoUri: String
        get() = sp.getString("photoUri", "") ?: ""
        set(v) = edit { putString("photoUri", v) }

    // Test alarms use small numbers so you can try the flow quickly.
    val target: Int get() = if (testMode) 3 else pushups
    val bedMs: Long get() = if (testMode) 60_000L else bedMin * 60_000L
    val limitMs: Long get() = if (testMode) 3 * 60_000L else limitMin * 60_000L
    val idleMs: Long get() = idleSec * 1000L

    fun resetChallenge(test: Boolean, mode: Int = MODE_WAKE) = edit {
        putInt("mode", mode)
        putInt("phase", 0)
        putInt("reps", 0)
        putLong("startedAt", 0L)
        putLong("bedStartedAt", 0L)
        putBoolean("testMode", test)
        putString("photoUri", "")
    }

    /** Updates the right streak when a real (non-test) alarm is completed. */
    fun recordDone(): Int {
        if (mode == MODE_LIBRARY) return 0
        val study = mode == MODE_STUDY
        val cur = if (study) studyStreak else streak
        if (testMode) return cur
        val last = if (study) studyLastDone else lastDone
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        val today = fmt.format(cal.time)
        if (last == today) return cur
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = fmt.format(cal.time)
        val n = if (last == yesterday) cur + 1 else 1
        if (study) { studyStreak = n; studyLastDone = today } else { streak = n; lastDone = today }
        return n
    }

    companion object {
        const val MODE_WAKE = 0
        const val MODE_STUDY = 1
        const val MODE_LIBRARY = 2
    }
}
