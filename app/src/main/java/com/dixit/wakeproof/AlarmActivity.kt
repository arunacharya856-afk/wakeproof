package com.dixit.wakeproof

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The wake-up screen: Start -> push-ups (counted with the proximity sensor) -> make bed -> photo check -> done.
 */
class AlarmActivity : ComponentActivity(), SensorEventListener {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var sensorManager: SensorManager? = null
    private var proximity: Sensor? = null
    private var sensorOn = false
    private var wasFar = true
    private var lastRepAt = 0L
    private var lastActivityAt = 0L
    private var inCamera = false
    private var netFails = 0

    private lateinit var clockTv: TextView
    private lateinit var soundTv: TextView
    private lateinit var titleTv: TextView
    private lateinit var bigTv: TextView
    private lateinit var infoTv: TextView
    private lateinit var timerTv: TextView
    private lateinit var tapPad: TextView
    private lateinit var actionBtn: Button
    private lateinit var altBtn: Button
    private lateinit var previewView: PreviewView
    private lateinit var camTv: TextView
    private var counter: PushupCounter? = null
    /** Camera refused or broken: fall back to tapping. */
    private var camUnavailable = false
    /** Result of the last photo check, kept on screen until the next one. */
    private var lastMsg: String? = null
    private lateinit var resultTv: TextView

    private val tick = object : Runnable {
        override fun run() {
            updateSound()
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        sensorManager = getSystemService(SensorManager::class.java)
        proximity = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        lastActivityAt = System.currentTimeMillis()

        // If the app was restarted mid-check, go back to the photo step.
        if (prefs.phase == PH_CHECK) prefs.phase = photoPhase()

        buildUi()

        if (!prefs.ringing && prefs.phase != PH_DONE) {
            finish()
            return
        }
        if (prefs.phase == PH_PUSH && !isStudy() && !isLib()) startCounter()
        render()
    }

    override fun onDestroy() {
        counter?.stop()
        super.onDestroy()
    }

    private fun hasCamera() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCounter() {
        if (!hasCamera()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM)
            return
        }
        if (counter == null) {
            counter = PushupCounter(
                this, previewView,
                onRep = { onRepSignal(minGapMs = 900) },
                onStatus = { camTv.text = it },
                onError = { msg ->
                    camUnavailable = true
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    render()
                }
            )
        }
        counter?.start()
    }

    private fun stopCounter() {
        counter?.stop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAM) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (prefs.phase == PH_PUSH) startCounter()
        } else {
            camUnavailable = true
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        inCamera = false
        if (prefs.phase == PH_PUSH) registerSensor()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        unregisterSensor()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the screen (home button, switching apps, locking) brings the sound back right away —
        // except during the library countdown, when you're supposed to put the phone away and walk.
        if (isLib() && libLeft() > 0) return
        if (!inCamera && prefs.ringing && prefs.phase != PH_DONE) {
            AlarmService.command(this, AlarmService.ACTION_LOUD)
        }
    }

    @Deprecated("Back is blocked on purpose")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (prefs.phase == PH_DONE) {
            super.onBackPressed()
        } else {
            Toast.makeText(this, "Finish your routine first 💪", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        val pad = Ui.dp(this, 22)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(this@AlarmActivity, 36), pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        clockTv = Ui.text(this, "", 54f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }
        soundTv = Ui.text(this, "", 15f, Ui.MUTED).apply { gravity = Gravity.CENTER }
        col.addView(clockTv, Ui.lp(this, 0))
        col.addView(soundTv, Ui.lp(this, 4))

        val card = Ui.card(this)
        titleTv = Ui.text(this, "", 22f, Ui.TEXT, true)
        bigTv = Ui.text(this, "", 44f, Ui.ACCENT, true).apply { gravity = Gravity.CENTER }
        infoTv = Ui.text(this, "", 16f, Ui.MUTED)
        timerTv = Ui.text(this, "", 15f, Ui.TEXT)
        card.addView(titleTv)
        card.addView(bigTv, Ui.lp(this, 14))
        card.addView(infoTv, Ui.lp(this, 12))
        card.addView(timerTv, Ui.lp(this, 12))
        resultTv = Ui.text(this, "", 15f, Ui.BAD, true).apply { setTextIsSelectable(true) }
        card.addView(resultTv, Ui.lp(this, 12))
        col.addView(card, Ui.lp(this, 28))

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            visibility = View.GONE
        }
        col.addView(previewView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 220)
        ).apply { topMargin = Ui.dp(this@AlarmActivity, 14) })
        camTv = Ui.text(this, "", 14f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        col.addView(camTv, Ui.lp(this, 6))

        tapPad =Ui.text(this, "TAP HERE WITH YOUR NOSE\n(at the bottom of each push-up)", 18f, Ui.DARK_TEXT, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = Ui.dp(this@AlarmActivity, 22).toFloat()
                setColor(Ui.ACCENT)
            }
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) onRepSignal(minGapMs = 800)
                true
            }
        }
        col.addView(tapPad, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 240)
        ).apply { topMargin = Ui.dp(this@AlarmActivity, 18) })

        actionBtn = Ui.button(this, "Start") { onAction() }
        col.addView(actionBtn, Ui.lp(this, 22))
        altBtn = Ui.button(this, "", Ui.CARD, Ui.TEXT) { onAlt() }
        col.addView(altBtn, Ui.lp(this, 10))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(col)
        }
        setContentView(scroll)
    }

    private fun render() {
        val now = System.currentTimeMillis()
        clockTv.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(now))
        val phase = prefs.phase
        val loud = AlarmService.isLoud

        soundTv.text = when {
            phase == PH_DONE -> "Alarm off"
            loud && phase == PH_IDLE -> "🔊 Ringing — tap the button below to silence it"
            loud -> "🔊 Sound is back — keep going to quiet it"
            else -> "🔇 Quiet while you keep moving"
        }
        soundTv.setTextColor(if (loud && phase != PH_DONE) Ui.BAD else Ui.MUTED)

        val showResult = lastMsg != null && phase != PH_DONE && phase != PH_CHECK
        resultTv.text = if (showResult) lastMsg else ""
        resultTv.visibility = if (showResult) View.VISIBLE else View.GONE
        tapPad.visibility = View.GONE
        previewView.visibility = View.GONE
        camTv.visibility = View.GONE
        altBtn.visibility = View.GONE
        actionBtn.visibility = View.VISIBLE
        bigTv.setTextColor(Ui.ACCENT)

        if (isStudy()) {
            renderStudy(phase, now)
            return
        }
        if (isLib()) {
            renderLib(phase)
            return
        }

        when (phase) {
            PH_IDLE -> {
                titleTv.text = if (prefs.testMode) "Test run" else "Rise and shine"
                bigTv.text = "${prefs.target} push-ups\n+ make your bed"
                bigTv.textSize = 30f
                infoTv.text = "Tap Start and the sound stops. It comes back if you stop moving, " +
                    "leave this screen, or run out of time."
                timerTv.text = ""
                actionBtn.text = "Start"
            }
            PH_PUSH -> {
                titleTv.text = "Push-ups"
                bigTv.textSize = 64f
                bigTv.text = "${prefs.reps} / ${prefs.target}"
                if (!camUnavailable) {
                    infoTv.text = "Lay the phone face-up under your face, top of the phone pointing the same " +
                        "way as your head. Keep your face in the preview — going down close to the phone = 1 rep."
                    previewView.visibility = View.VISIBLE
                    camTv.visibility = View.VISIBLE
                } else {
                    infoTv.text = "Camera isn't available, so tap the pad with your nose at the bottom of each rep."
                    tapPad.visibility = View.VISIBLE
                }
                val idleLeft = (prefs.idleMs - (now - lastActivityAt)) / 1000
                val totalLeft = prefs.limitMs - (now - prefs.startedAt)
                timerTv.text = if (totalLeft <= 0) "⏰ Time limit passed — finish to stop the alarm"
                else "Next rep within ${idleLeft.coerceAtLeast(0)}s · ${fmtLeft(totalLeft)} left overall"
                actionBtn.visibility = View.GONE
            }
            PH_BED -> {
                titleTv.text = "Now make your bed"
                bigTv.textSize = 26f
                bigTv.setTextColor(Ui.GOOD)
                bigTv.text = "✔ ${prefs.target} push-ups done"
                infoTv.text = "Make your bed, then take a photo of it" +
                    if (prefs.apiKey.isBlank()) "." else " — ${BedChecker.providerName(prefs.apiKey)} will check it."
                val bedLeft = prefs.bedMs - (now - prefs.bedStartedAt)
                timerTv.text = if (bedLeft > 0) "${fmtLeft(bedLeft)} of quiet left to make it"
                else "⏰ Bed time is up — the alarm rings until your photo passes"
                actionBtn.text = "📷  Take photo of bed"
                if (netFails >= 2) {
                    altBtn.visibility = View.VISIBLE
                    altBtn.text = "Can't reach the AI — accept without check"
                }
            }
            PH_CHECK -> {
                titleTv.text = "Checking your bed…"
                bigTv.textSize = 26f
                bigTv.text = "🤔"
                infoTv.text = "Hang on a few seconds."
                timerTv.text = ""
                actionBtn.visibility = View.GONE
            }
            PH_DONE -> {
                titleTv.text = "You're up."
                bigTv.textSize = 40f
                bigTv.setTextColor(Ui.GOOD)
                bigTv.text = if (prefs.testMode) "Test passed ✅" else "🔥 ${prefs.streak}-day streak"
                infoTv.text = "Alarm off. Have a good day."
                timerTv.text = ""
                actionBtn.text = "Close"
            }
        }
    }

    private fun isStudy() = prefs.mode == Prefs.MODE_STUDY

    private fun isLib() = prefs.mode == Prefs.MODE_LIBRARY

    /** Milliseconds left to reach the library (negative once time is up). */
    private fun libLeft(): Long = prefs.libMs - (System.currentTimeMillis() - prefs.startedAt)

    /** The step you return to after a failed photo check. */
    private fun photoPhase() = if (isStudy() || isLib()) PH_STUDY else PH_BED

    private fun renderLib(phase: Int) {
        val left = libLeft()
        when (phase) {
            PH_STUDY, PH_IDLE -> {
                titleTv.text = "Get to the library 📚"
                bigTv.textSize = 56f
                if (left > 0) {
                    bigTv.text = fmtLeft(left)
                    soundTv.text = "🔇 Quiet until time's up"
                    soundTv.setTextColor(Ui.MUTED)
                    infoTv.text = "Lock your phone and go. When you're there, open this and take a selfie " +
                        "with the library behind you (shelves or study tables)." +
                        if (prefs.apiKey.isBlank()) "\n\nNo AI key saved, so any fresh photo counts." else ""
                    timerTv.text = "Rings at the end until your library selfie passes."
                } else {
                    bigTv.setTextColor(Ui.BAD)
                    bigTv.text = "Time's up"
                    infoTv.text = "It keeps ringing until a library selfie passes."
                    timerTv.text = ""
                }
                actionBtn.text = "🤳  Selfie at the library"
                if (netFails >= 2) {
                    altBtn.visibility = View.VISIBLE
                    altBtn.text = "Can't reach the AI — accept without check"
                }
            }
            PH_CHECK -> {
                titleTv.text = "Checking your selfie…"
                bigTv.textSize = 26f
                bigTv.text = "🤔"
                infoTv.text = "Hang on a few seconds."
                timerTv.text = ""
                actionBtn.visibility = View.GONE
            }
            PH_DONE -> {
                titleTv.text = "You made it."
                bigTv.textSize = 40f
                bigTv.setTextColor(Ui.GOOD)
                bigTv.text = "📚 Now study"
                infoTv.text = "Alarm off. Tired brains still learn — start with easy Anki reviews."
                timerTv.text = ""
                actionBtn.text = "Close"
            }
        }
    }

    private fun renderStudy(phase: Int, now: Long) {
        val target = prefs.studyCards
        when (phase) {
            PH_IDLE -> {
                titleTv.text = if (prefs.testMode) "Desk check test" else "Desk time 📚"
                bigTv.textSize = 30f
                bigTv.text = "$target Anki cards"
                infoTv.text = "Sit down at your desk and tap the button. The sound stops while you study, " +
                    "then photograph your Anki screen showing \"Studied N cards today\"."
                timerTv.text = ""
                actionBtn.text = "I'm at my desk"
            }
            PH_STUDY -> {
                titleTv.text = "Do your cards"
                bigTv.textSize = 30f
                bigTv.text = "$target+ cards today"
                infoTv.text = "When you're done, go to Anki's main Decks screen (or Stats) and photograph the " +
                    "\"Studied … cards today\" line. Get close enough that it's readable." +
                    if (prefs.apiKey.isBlank()) "\n\nNo AI key saved, so any fresh photo counts." else ""
                val left = prefs.studyMs - (now - prefs.startedAt)
                timerTv.text = if (left > 0) "${fmtLeft(left)} of quiet left"
                else "⏰ Quiet time is up — rings until your Anki photo passes"
                actionBtn.text = "📷  Photo of Anki screen"
                if (netFails >= 2) {
                    altBtn.visibility = View.VISIBLE
                    altBtn.text = "Can't reach the AI — accept without check"
                }
            }
            PH_CHECK -> {
                titleTv.text = "Reading your Anki screen…"
                bigTv.textSize = 26f
                bigTv.text = "🤔"
                infoTv.text = "Hang on a few seconds."
                timerTv.text = ""
                actionBtn.visibility = View.GONE
            }
            PH_DONE -> {
                titleTv.text = "Studying. Nice."
                bigTv.textSize = 40f
                bigTv.setTextColor(Ui.GOOD)
                bigTv.text = if (prefs.testMode) "Test passed ✅" else "📚 ${prefs.studyStreak}-day desk streak"
                infoTv.text = "Alarm off. Keep going."
                timerTv.text = ""
                actionBtn.text = "Close"
            }
        }
    }

    private fun startStudy() {
        prefs.startedAt = System.currentTimeMillis()
        prefs.phase = PH_STUDY
        updateSound()
        render()
    }

    private fun fmtLeft(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ---------------- Sound logic ----------------

    private fun updateSound() {
        if (!prefs.ringing || prefs.phase == PH_DONE) return
        if (isLib()) {
            // Quiet for the whole remaining countdown, even if this screen closes.
            val left = libLeft()
            if (prefs.phase == PH_CHECK) AlarmService.command(this, AlarmService.ACTION_QUIET, 5000L)
            else if (left > 0) AlarmService.command(this, AlarmService.ACTION_QUIET, left)
            else AlarmService.command(this, AlarmService.ACTION_LOUD)
            return
        }
        val now = System.currentTimeMillis()
        val overLimit = prefs.startedAt > 0 && now - prefs.startedAt > prefs.limitMs
        val quiet = when (prefs.phase) {
            PH_PUSH -> !overLimit && now - lastActivityAt < prefs.idleMs
            PH_BED -> !overLimit && now - prefs.bedStartedAt < prefs.bedMs
            PH_STUDY -> now - prefs.startedAt < prefs.studyMs
            PH_CHECK -> true
            else -> false
        }
        if (quiet) AlarmService.command(this, AlarmService.ACTION_QUIET, 5000L)
        else AlarmService.command(this, AlarmService.ACTION_LOUD)
    }

    // ---------------- Actions ----------------

    private fun onAction() {
        when (prefs.phase) {
            PH_IDLE -> if (isStudy()) startStudy() else startPushups()
            PH_BED, PH_STUDY -> takePhoto()
            PH_DONE -> finishAndRemoveTask()
        }
    }

    private fun onAlt() {
        if ((prefs.phase == PH_BED || prefs.phase == PH_STUDY) && netFails >= 2) complete()
    }

    private fun startPushups() {
        val now = System.currentTimeMillis()
        prefs.startedAt = now
        prefs.reps = 0
        prefs.phase = PH_PUSH
        lastActivityAt = now
        wasFar = true
        registerSensor()
        startCounter()
        updateSound()
        render()
    }

    private fun startBed() {
        unregisterSensor()
        stopCounter()
        prefs.bedStartedAt = System.currentTimeMillis()
        prefs.phase = PH_BED
        buzz(250)
        updateSound()
        render()
    }

    private fun complete() {
        prefs.recordDone()
        netFails = 0
        // Desk time already arrived during the wake-up routine: go straight into the study window.
        if (!isStudy() && prefs.studyQueued) {
            prefs.studyQueued = false
            prefs.resetChallenge(test = false, mode = Prefs.MODE_STUDY)
            Toast.makeText(this, "Wake-up done ✅ — desk time already started", Toast.LENGTH_LONG).show()
            startStudy()
            return
        }
        prefs.phase = PH_DONE
        prefs.ringing = false
        AlarmService.command(this, AlarmService.ACTION_STOP)
        render()
        handler.postDelayed({ if (!isFinishing) finishAndRemoveTask() }, 6000)
    }

    // ---------------- Push-up counting ----------------

    private fun registerSensor() {
        val s = proximity ?: return
        if (sensorOn) return
        sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        sensorOn = true
    }

    private fun unregisterSensor() {
        if (!sensorOn) return
        sensorManager?.unregisterListener(this)
        sensorOn = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PROXIMITY) return
        val near = event.values[0] < event.sensor.maximumRange
        if (near) {
            if (wasFar) onRepSignal(minGapMs = 900)
            wasFar = false
        } else {
            wasFar = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onRepSignal(minGapMs: Long) {
        if (prefs.phase != PH_PUSH) return
        val now = System.currentTimeMillis()
        if (now - lastRepAt < minGapMs) return
        lastRepAt = now
        lastActivityAt = now
        val reps = prefs.reps + 1
        prefs.reps = reps
        buzz(40)
        if (reps >= prefs.target) startBed() else {
            updateSound()
            render()
        }
    }

    @Suppress("DEPRECATION")
    private fun buzz(ms: Long) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else getSystemService(Vibrator::class.java)
            if (!AlarmService.isLoud) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {}
    }

    // ---------------- Bed photo ----------------

    private fun takePhoto() {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { launchCamera() }
            })
        } else {
            launchCamera()
        }
    }

    @Suppress("DEPRECATION")
    private fun launchCamera() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "wakeproof_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WakeProof")
        }
        val uri = try {
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) { null }
        if (uri == null) {
            Toast.makeText(this, "Couldn't prepare the photo — try again", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.photoUri = uri.toString()
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("photo", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (isLib()) {
                // Ask for the front (selfie) camera; most camera apps honour at least one of these.
                putExtra("android.intent.extras.CAMERA_FACING", 1)
                putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            }
        }
        inCamera = true
        // Give 2 minutes of quiet in the camera (or the rest of the library countdown); then the sound returns.
        val grace = if (isLib()) maxOf(120_000L, libLeft()) else 120_000L
        AlarmService.command(this, AlarmService.ACTION_QUIET, grace)
        try {
            startActivityForResult(i, REQ_PHOTO)
        } catch (e: ActivityNotFoundException) {
            inCamera = false
            deletePhoto(uri)
            Toast.makeText(this, "No camera app found", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Uses the platform result API to avoid extra libraries")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTO) return
        inCamera = false
        val uri = prefs.photoUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) } ?: return
        if (resultCode != RESULT_OK) {
            deletePhoto(uri)
            return
        }
        verifyPhoto(uri)
    }

    private fun verifyPhoto(uri: Uri) {
        prefs.phase = PH_CHECK
        render()
        val key = prefs.apiKey
        val study = isStudy()
        val lib = isLib()
        val target = prefs.studyCards
        Thread {
            val bmp = BedChecker.loadBitmap(this, uri)
            val result: BedChecker.Result = when {
                bmp == null -> BedChecker.Result.Failed("Photo was empty")
                key.isBlank() -> BedChecker.Result.Made // No API key: any fresh photo counts.
                study -> BedChecker.checkAnki(key, bmp, target)
                lib -> BedChecker.checkLibrary(key, bmp)
                else -> BedChecker.check(key, bmp)
            }
            deletePhoto(uri)
            runOnUiThread { onCheckResult(result, bmp == null) }
        }.start()
    }

    private fun onCheckResult(result: BedChecker.Result, emptyPhoto: Boolean) {
        if (isFinishing) return
        when (result) {
            is BedChecker.Result.Made -> {
                lastMsg = null
                complete()
            }
            is BedChecker.Result.NotMade -> {
                prefs.phase = photoPhase()
                lastMsg = "❌ Not yet: ${result.reason}"
            }
            is BedChecker.Result.Failed -> {
                prefs.phase = photoPhase()
                if (!emptyPhoto) netFails++
                lastMsg = "⚠️ Check failed: ${result.message}"
            }
        }
        render()
    }

    private fun deletePhoto(uri: Uri) {
        try { contentResolver.delete(uri, null, null) } catch (e: Exception) {}
    }

    companion object {
        const val PH_IDLE = 0
        const val PH_PUSH = 1
        const val PH_BED = 2
        const val PH_CHECK = 3
        const val PH_DONE = 4
        const val PH_STUDY = 5
        const val REQ_PHOTO = 42
        const val REQ_CAM = 43
    }
}
