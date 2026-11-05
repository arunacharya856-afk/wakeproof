package com.dixit.wakeproof

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Settings screen. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private var hour = 7
    private var minute = 0
    private val selectedDays = mutableSetOf<Int>()

    private lateinit var statusTv: TextView
    private lateinit var streakTv: TextView
    private lateinit var enabledSw: Switch
    private lateinit var timeBtn: Button
    private val dayViews = mutableMapOf<Int, TextView>()
    private lateinit var pushupsEt: EditText
    private lateinit var bedEt: EditText
    private lateinit var idleEt: EditText
    private lateinit var limitEt: EditText
    private lateinit var vibOnlySw: Switch
    private lateinit var rampSw: Switch
    private lateinit var keyEt: EditText
    private lateinit var permBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        AlarmService.createChannel(this)
        hour = prefs.hour
        minute = prefs.minute
        selectedDays.addAll(prefs.days)
        buildUi()
        Scheduler.scheduleNext(this)
    }

    override fun onResume() {
        super.onResume()
        // No sneaking into settings to turn off a ringing alarm.
        if (prefs.ringing) {
            startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        refreshPermissions()
        refreshStatus()
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        val pad = Ui.dp(this, 20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(this@MainActivity, 28), pad, Ui.dp(this@MainActivity, 40))
        }

        col.addView(Ui.text(this, "WakeProof", 30f, Ui.TEXT, true))
        col.addView(Ui.text(this, "Push-ups + made bed, or it keeps ringing.", 15f, Ui.MUTED), Ui.lp(this, 2))

        statusTv = Ui.text(this, "", 17f, Ui.TEXT, true)
        streakTv = Ui.text(this, "", 15f, Ui.MUTED)
        val statusCard = Ui.card(this)
        statusCard.addView(statusTv)
        statusCard.addView(streakTv, Ui.lp(this, 4))
        col.addView(statusCard, Ui.lp(this, 18))

        permBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(permBox, Ui.lp(this, 12))

        // Alarm card
        val alarm = Ui.card(this)
        enabledSw = Switch(this).apply {
            text = "Alarm on"
            textSize = 18f
            setTextColor(Ui.TEXT)
            isChecked = prefs.enabled
        }
        alarm.addView(enabledSw)
        timeBtn = Ui.button(this, "", Ui.ACCENT) { pickTime() }
        timeBtn.textSize = 28f
        alarm.addView(timeBtn, Ui.lp(this, 12))
        alarm.addView(buildDaysRow(), Ui.lp(this, 14))
        col.addView(alarm, Ui.lp(this, 12))

        // Routine card
        val routine = Ui.card(this)
        routine.addView(Ui.text(this, "Routine", 18f, Ui.TEXT, true))
        pushupsEt = numberField(prefs.pushups)
        routine.addView(row("Push-ups", pushupsEt), Ui.lp(this, 10))
        bedEt = numberField(prefs.bedMin)
        routine.addView(row("Minutes to make bed (quiet)", bedEt), Ui.lp(this, 8))
        idleEt = numberField(prefs.idleSec)
        routine.addView(row("Seconds between reps before sound returns", idleEt), Ui.lp(this, 8))
        limitEt = numberField(prefs.limitMin)
        routine.addView(row("Total quiet time limit (minutes)", limitEt), Ui.lp(this, 8))
        col.addView(routine, Ui.lp(this, 12))

        // Sound card
        val sound = Ui.card(this)
        sound.addView(Ui.text(this, "Sound", 18f, Ui.TEXT, true))
        rampSw = switchRow("Gentle start (volume ramps up)", prefs.rampUp)
        sound.addView(rampSw, Ui.lp(this, 10))
        vibOnlySw = switchRow("Vibrate only (no sound)", prefs.vibrateOnly)
        sound.addView(vibOnlySw, Ui.lp(this, 10))
        sound.addView(
            Ui.text(this, "Loudness follows your phone's alarm volume.", 13f, Ui.MUTED),
            Ui.lp(this, 8)
        )
        col.addView(sound, Ui.lp(this, 12))

        // AI card
        val ai = Ui.card(this)
        ai.addView(Ui.text(this, "Bed check with Claude (optional)", 18f, Ui.TEXT, true))
        ai.addView(
            Ui.text(
                this,
                "Paste a Claude API key (from console.anthropic.com) so Claude checks the bed photo. " +
                    "Without a key, any fresh photo counts.",
                14f, Ui.MUTED
            ),
            Ui.lp(this, 6)
        )
        keyEt = EditText(this).apply {
            setText(prefs.apiKey)
            hint = "sk-ant-…"
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        ai.addView(keyEt, Ui.lp(this, 6))
        col.addView(ai, Ui.lp(this, 12))

        col.addView(Ui.button(this, "Save") { save(showToast = true) }, Ui.lp(this, 18))
        col.addView(
            Ui.button(this, "Test alarm now (3 push-ups)", Ui.CARD, Ui.TEXT) { confirmTest() },
            Ui.lp(this, 10)
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(col)
        }
        setContentView(scroll)
        updateTimeButton()
    }

    private fun buildDaysRow(): LinearLayout {
        val rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = listOf("S", "M", "T", "W", "T", "F", "S")
        for (d in 1..7) {
            val size = Ui.dp(this, 38)
            val tv = TextView(this).apply {
                text = labels[d - 1]
                gravity = Gravity.CENTER
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener {
                    if (selectedDays.contains(d)) selectedDays.remove(d) else selectedDays.add(d)
                    styleDay(this, selectedDays.contains(d))
                }
            }
            styleDay(tv, selectedDays.contains(d))
            dayViews[d] = tv
            val lp = LinearLayout.LayoutParams(0, size, 1f).apply {
                marginStart = Ui.dp(this@MainActivity, 2)
                marginEnd = Ui.dp(this@MainActivity, 2)
            }
            rowL.addView(tv, lp)
        }
        return rowL
    }

    private fun styleDay(tv: TextView, on: Boolean) {
        tv.background = GradientDrawable().apply {
            cornerRadius = Ui.dp(this@MainActivity, 19).toFloat()
            setColor(if (on) Ui.ACCENT else Ui.BG)
        }
        tv.setTextColor(if (on) Ui.DARK_TEXT else Ui.MUTED)
    }

    private fun numberField(v: Int) = EditText(this).apply {
        setText(v.toString())
        inputType = InputType.TYPE_CLASS_NUMBER
        setTextColor(Ui.TEXT)
        gravity = Gravity.CENTER
        isSingleLine = true
    }

    private fun row(label: String, field: EditText): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        r.addView(Ui.text(this, label, 15f, Ui.TEXT), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(field, LinearLayout.LayoutParams(Ui.dp(this, 80), LinearLayout.LayoutParams.WRAP_CONTENT))
        return r
    }

    private fun switchRow(label: String, checked: Boolean) = Switch(this).apply {
        text = label
        textSize = 15f
        setTextColor(Ui.TEXT)
        isChecked = checked
    }

    private fun pickTime() {
        TimePickerDialog(this, { _, h, m ->
            hour = h
            minute = m
            updateTimeButton()
            enabledSw.isChecked = true
            save(showToast = true)
        }, hour, minute, false).show()
    }

    private fun updateTimeButton() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        timeBtn.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
    }

    // ---------------- Saving ----------------

    private fun EditText.intOr(d: Int): Int = text.toString().trim().toIntOrNull() ?: d

    private fun save(showToast: Boolean) {
        prefs.hour = hour
        prefs.minute = minute
        prefs.days = selectedDays.toSet()
        prefs.enabled = enabledSw.isChecked
        prefs.pushups = pushupsEt.intOr(20).coerceIn(1, 200)
        prefs.bedMin = bedEt.intOr(3).coerceIn(1, 15)
        prefs.idleSec = idleEt.intOr(60).coerceIn(15, 300)
        prefs.limitMin = limitEt.intOr(10).coerceIn(2, 60)
        prefs.rampUp = rampSw.isChecked
        prefs.vibrateOnly = vibOnlySw.isChecked
        prefs.apiKey = keyEt.text.toString().trim()
        pushupsEt.setText(prefs.pushups.toString())
        bedEt.setText(prefs.bedMin.toString())
        idleEt.setText(prefs.idleSec.toString())
        limitEt.setText(prefs.limitMin.toString())

        val t = Scheduler.scheduleNext(this)
        if (showToast) {
            val msg = when {
                !prefs.enabled -> "Alarm is off"
                t == null -> "Pick at least one day"
                else -> "Alarm set for " + fmtTime(t)
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        refreshStatus()
    }

    private fun fmtTime(t: Long): String =
        SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(t))

    private fun refreshStatus() {
        val t = if (prefs.enabled) Scheduler.nextTrigger(prefs) else null
        statusTv.text = if (t != null) "⏰ Next alarm: ${fmtTime(t)}" else "Alarm is off"
        val s = prefs.streak
        streakTv.text = if (s > 0) "🔥 Streak: $s day${if (s == 1) "" else "s"}" else "Finish your first morning to start a streak."
    }

    private fun confirmTest() {
        AlertDialog.Builder(this)
            .setTitle("Test the alarm?")
            .setMessage("It will ring right now and won't stop until you do 3 push-ups and photograph your bed.")
            .setPositiveButton("Start test") { _, _ ->
                save(showToast = false)
                prefs.resetChallenge(test = true)
                prefs.ringing = true
                AlarmService.start(this)
                startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- Permissions ----------------

    private fun refreshPermissions() {
        permBox.removeAllViews()
        val issues = mutableListOf<Pair<String, () -> Unit>>()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            issues += "Allow notifications (needed for the alarm screen)" to {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                issues += "Allow full-screen alarms (shows over the lock screen)" to {
                    openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                }
            }
        }
        if (!Scheduler.canScheduleExact(this)) {
            issues += "Allow exact alarms (rings on time)" to {
                openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            }
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            issues += "Turn off battery limits for WakeProof (so it can't be put to sleep)" to {
                openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            }
        }

        if (issues.isEmpty()) {
            permBox.addView(Ui.text(this, "✅ All set — the alarm can wake you up.", 14f, Ui.GOOD))
            return
        }
        val card = Ui.card(this)
        card.addView(Ui.text(this, "Finish setup (${issues.size} left)", 17f, Ui.BAD, true))
        for ((label, action) in issues) {
            card.addView(Ui.button(this, label, Ui.BG, Ui.TEXT) { action() }, Ui.lp(this, 10))
        }
        permBox.addView(card)
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (x: Exception) {
                Toast.makeText(this, "Open Settings › Apps › WakeProof", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
    }
}
