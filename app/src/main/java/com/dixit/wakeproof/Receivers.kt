package com.dixit.wakeproof

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires at alarm time. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        val mode = intent.getIntExtra(EXTRA_MODE, Prefs.MODE_WAKE)
        // Ignore a stray alarm left over from an old time setting (it must be within 15 min of the current setting).
        if (!matchesSettings(p, mode)) {
            Scheduler.scheduleNext(ctx)
            return
        }
        if (p.ringing) {
            // Still doing the wake-up routine: run the desk check straight after it.
            if (mode == Prefs.MODE_STUDY && p.mode == Prefs.MODE_WAKE) p.studyQueued = true
            Scheduler.scheduleNext(ctx)
            return
        }
        p.studyQueued = false
        p.resetChallenge(test = false, mode = mode)
        p.ringing = true
        try {
            AlarmService.start(ctx)
        } catch (e: Exception) {
            // Nothing else we can do; the notification permission screen in settings covers this.
        }
        Scheduler.scheduleNext(ctx)
    }

    private fun matchesSettings(p: Prefs, mode: Int): Boolean {
        val on = if (mode == Prefs.MODE_STUDY) p.studyEnabled else p.enabled
        if (!on) return false
        val h = if (mode == Prefs.MODE_STUDY) p.studyHour else p.hour
        val m = if (mode == Prefs.MODE_STUDY) p.studyMinute else p.minute
        val now = java.util.Calendar.getInstance()
        val nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        var diff = Math.abs(nowMin - (h * 60 + m))
        if (diff > 12 * 60) diff = 24 * 60 - diff
        return diff <= 15
    }

    companion object {
        const val ACTION_FIRE = "com.dixit.wakeproof.FIRE"
        const val EXTRA_MODE = "mode"
    }
}

/** Re-arms the alarm after a reboot, app update or clock change; resumes an unfinished alarm. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.scheduleNext(ctx)
        if (Prefs(ctx).ringing) {
            try {
                AlarmService.start(ctx)
            } catch (e: Exception) {
            }
        }
    }
}
