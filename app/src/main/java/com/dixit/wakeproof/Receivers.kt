package com.dixit.wakeproof

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires at alarm time. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        val mode = intent.getIntExtra(EXTRA_MODE, Prefs.MODE_WAKE)
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
