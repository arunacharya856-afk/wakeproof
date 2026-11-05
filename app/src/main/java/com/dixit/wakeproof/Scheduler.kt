package com.dixit.wakeproof

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object Scheduler {
    private const val REQ_ALARM = 1001
    private const val REQ_SHOW = 1002

    fun nextTrigger(p: Prefs, now: Long = System.currentTimeMillis()): Long? {
        val days = p.days
        if (days.isEmpty()) return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, p.hour)
            set(Calendar.MINUTE, p.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        repeat(8) {
            if (days.contains(cal.get(Calendar.DAY_OF_WEEK))) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    private fun alarmIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE)
        return PendingIntent.getBroadcast(
            ctx, REQ_ALARM, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Cancels any pending alarm and schedules the next one. Returns the trigger time, or null if off. */
    fun scheduleNext(ctx: Context): Long? {
        val p = Prefs(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx)
        am.cancel(pi)
        if (!p.enabled) return null
        val t = nextTrigger(p) ?: return null
        val show = PendingIntent.getActivity(
            ctx, REQ_SHOW, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(t, show), pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        }
        return t
    }

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
}
