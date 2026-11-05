package com.dixit.wakeproof

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires at alarm time. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        p.resetChallenge(test = false)
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
