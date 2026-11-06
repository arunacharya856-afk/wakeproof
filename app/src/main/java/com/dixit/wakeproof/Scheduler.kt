package com.dixit.wakeproof

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object Scheduler {
    private const val REQ_WAKE = 1001
    private const val REQ_SHOW = 1002
    private const val REQ_STUDY = 1003

    fun nextAt(days: Set<Int>, hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long? {
        if (days.isEmpty()) return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
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

    fun nextTrigger(p: Prefs): Long? = nextAt(p.days, p.hour, p.minute)

    fun nextStudy(p: Prefs): Long? = nextAt(p.days, p.studyHour, p.studyMinute)

    private fun alarmIntent(ctx: Context, mode: Int): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE + mode)
            .putExtra(AlarmReceiver.EXTRA_MODE, mode)
        return PendingIntent.getBroadcast(
            ctx, if (mode == Prefs.MODE_STUDY) REQ_STUDY else REQ_WAKE, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun set(ctx: Context, t: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            ctx, REQ_SHOW, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(t, show), pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        }
    }

    /** Cancels and re-schedules both alarms. Returns the next wake-up time, or null if off. */
    fun scheduleNext(ctx: Context): Long? {
        val p = Prefs(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)

        // Builds 1–4 scheduled alarms under an older name; cancel any leftover so an old time can't ring.
        val legacy = PendingIntent.getBroadcast(
            ctx, REQ_WAKE,
            Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (legacy != null) {
            am.cancel(legacy)
            legacy.cancel()
        }

        val study = alarmIntent(ctx, Prefs.MODE_STUDY)
        am.cancel(study)
        if (p.studyEnabled) nextStudy(p)?.let { set(ctx, it, study) }

        val wake = alarmIntent(ctx, Prefs.MODE_WAKE)
        am.cancel(wake)
        if (!p.enabled) return null
        val t = nextTrigger(p) ?: return null
        set(ctx, t, wake)
        return t
    }

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
}
