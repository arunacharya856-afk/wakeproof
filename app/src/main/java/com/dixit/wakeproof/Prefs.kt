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
        set(v) = edit { putBoolean("ringing", v) }
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

    fun resetChallenge(test: Boolean) = edit {
        putInt("phase", 0)
        putInt("reps", 0)
        putLong("startedAt", 0L)
        putLong("bedStartedAt", 0L)
        putBoolean("testMode", test)
        putString("photoUri", "")
    }

    /** Updates the streak when a real (non-test) alarm is completed. */
    fun recordDone(): Int {
        if (testMode) return streak
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        val today = fmt.format(cal.time)
        if (lastDone == today) return streak
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = fmt.format(cal.time)
        val newStreak = if (lastDone == yesterday) streak + 1 else 1
        streak = newStreak
        lastDone = today
        return newStreak
    }
}
