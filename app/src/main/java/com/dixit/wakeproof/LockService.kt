package com.dixit.wakeproof

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/**
 * "Lock mode": while an alarm is ringing, any other app (home screen, Settings, the uninstall
 * screen, Instagram…) bounces you straight back to the alarm, and the volume buttons do nothing.
 *
 * Always allowed: phone/emergency dialer, the camera during a photo step, Anki during the desk check,
 * the keyboard, permission pop-ups and the system UI. Safety net: the lock lets go after 60 minutes.
 */
class LockService : AccessibilityService() {

    private var lastBounce = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (!locked(this)) return
        if (allowed(pkg)) return

        val now = System.currentTimeMillis()
        if (now - lastBounce < 800) return
        lastBounce = now
        try {
            startActivity(
                Intent(this, AlarmActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
            Toast.makeText(this, "Finish your routine first 💪", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /** Swallow volume buttons while ringing. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        val isVolume = k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_UP ||
            k == KeyEvent.KEYCODE_VOLUME_MUTE
        return isVolume && locked(this)
    }

    override fun onInterrupt() {}

    private fun allowed(pkg: String): Boolean {
        if (pkg == packageName) return true
        if (pkg == "com.android.systemui" || pkg == "android") return true
        if (pkg.contains("permissioncontroller")) return true
        if (EMERGENCY.any { pkg == it } || pkg.contains("dialer") || pkg.contains("incallui") ||
            pkg.contains("emergency") || pkg.contains("telecom")
        ) return true
        if (keyboards().contains(pkg)) return true

        val p = Prefs(this)
        // Camera only while the alarm screen sent you there for a photo.
        if (System.currentTimeMillis() < p.cameraUntil && (pkg.contains("camera") || pkg == cameraPkg())) return true
        // Anki on the phone counts as studying during the desk check.
        if (p.mode == Prefs.MODE_STUDY && pkg == "com.ichi2.anki") return true
        return false
    }

    private fun cameraPkg(): String? = try {
        packageManager.resolveActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE), 0)?.activityInfo?.packageName
    } catch (e: Exception) { null }

    private fun keyboards(): Set<String> = try {
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
    } catch (e: Exception) { emptySet() }

    companion object {
        private val EMERGENCY = setOf(
            "com.android.phone", "com.android.server.telecom", "com.google.android.dialer",
            "com.samsung.android.dialer", "com.android.dialer", "com.android.emergency"
        )
        private const val MAX_LOCK_MS = 60 * 60 * 1000L

        /** True while the phone should be locked to the alarm screen. */
        fun locked(ctx: Context): Boolean {
            val p = Prefs(ctx)
            if (!p.ringing || p.phase == AlarmActivity.PH_DONE) return false
            val now = System.currentTimeMillis()
            if (p.ringSince > 0 && now - p.ringSince > MAX_LOCK_MS) return false
            // During the library countdown you need your phone to walk there.
            if (p.mode == Prefs.MODE_LIBRARY && now - p.startedAt < p.libMs) return false
            return true
        }

        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, LockService::class.java)
            return list.split(':').any {
                ComponentName.unflattenFromString(it) == me
            }
        }
    }
}
