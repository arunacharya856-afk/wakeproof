package com.dixit.wakeproof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Sends proof photos (bed, Anki screen) to Gemini or Claude and reads the verdict. */
object BedChecker {
    const val MODEL = "claude-haiku-5-5"
    const val GEMINI_MODEL = "gemini-2.5-flash"

    sealed class Result {
        object Made : Result()
        data class NotMade(val reason: String) : Result()
        data class Failed(val message: String) : Result()
    }

    private const val BED_PROMPT =
        "This photo comes from a morning alarm app: the user must make their bed and photograph it " +
        "to switch the alarm off. Is this a real, live photo of a bed that has been made " +
        "(covers pulled up and roughly straightened, pillows near the head)? Be fair, not strict — " +
        "dorm-level neat is enough. Say not made if there is no bed, the covers are clearly messy or " +
        "thrown back, or it looks like a photo of a screen or printed picture. " +
        "Reply with ONLY JSON: {\"made\": true or false, \"reason\": \"under 15 words\"}"

    private const val ANKI_PROMPT =
        "This photo comes from a study alarm app: the user must photograph their computer, tablet or phone " +
        "screen showing the Anki flashcard app to prove how many cards they have studied TODAY. " +
        "On Anki's main Decks screen this appears as text like \"Studied 34 cards in 12 minutes today\". " +
        "In AnkiDroid or Anki's Statistics screen it may appear as \"Today: Studied 34 cards\" or similar. " +
        "Read that number. Only count cards studied today — not cards due, new, or in other time periods. " +
        "If the screen is not Anki, or today's studied-card count is not visible or readable, use -1. " +
        "Reply with ONLY JSON: {\"cards\": number, \"reason\": \"under 15 words\"}"

    fun loadBitmap(ctx: Context, uri: Uri, maxSide: Int = 2000): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        }
    }

    /** Picks the AI from the key: Claude keys start with "sk-ant-"; anything else is treated as a Google/Gemini key. */
    fun providerName(key: String): String = if (isGemini(key)) "Gemini" else "Claude"

    private fun isGemini(key: String) = !key.trim().startsWith("sk-ant-")

    /** Is the bed made? */
    fun check(apiKey: String, bmp: Bitmap): Result {
        val (json, err) = ask(apiKey, bmp, BED_PROMPT, maxSide = 1024)
        if (json == null) return Result.Failed(err ?: "No answer")
        return if (json.optBoolean("made", false)) Result.Made
        else Result.NotMade(json.optString("reason", "Bed doesn't look made yet"))
    }

    /** Has the user studied at least [target] Anki cards today? Higher resolution so screen text is readable. */
    fun checkAnki(apiKey: String, bmp: Bitmap, target: Int): Result {
        val (json, err) = ask(apiKey, bmp, ANKI_PROMPT, maxSide = 1600)
        if (json == null) return Result.Failed(err ?: "No answer")
        val cards = json.optInt("cards", -1)
        val reason = json.optString("reason", "")
        return when {
            cards >= target -> Result.Made
            cards >= 0 -> Result.NotMade("Only $cards cards today — need $target")
            else -> Result.NotMade(if (reason.isNotBlank()) reason else "Couldn't find today's card count — get closer to the screen")
        }
    }

    private fun ask(apiKey: String, bmp: Bitmap, prompt: String, maxSide: Int): Pair<JSONObject?, String?> {
        val b64 = try {
            val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
            val img = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            } else bmp
            val bos = ByteArrayOutputStream()
            img.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            return null to "Couldn't read the photo"
        }
        val (text, err) = if (isGemini(apiKey)) askGemini(apiKey.trim(), b64, prompt) else askClaude(apiKey.trim(), b64, prompt)
        if (text == null) return null to err
        val who = providerName(apiKey)
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) return null to "Couldn't read $who's answer"
        return try {
            JSONObject(text.substring(s, e + 1)) to null
        } catch (x: Exception) {
            null to "Couldn't read $who's answer"
        }
    }

    private fun askGemini(apiKey: String, b64: String, prompt: String): Pair<String?, String?> {
        return try {
            val parts = JSONArray()
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
                .put(JSONObject().put("text", prompt))
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put(
                    "generationConfig", JSONObject()
                        .put("responseMimeType", "application/json")
                        .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                )

            val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 45000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).optJSONObject("error")?.optString("message") ?: ""
                } catch (e: Exception) { "" }
                val hint = if (code == 429) " (free limit reached — try again in a minute)" else ""
                return null to "Gemini error $code$hint ${msg.take(100)}".trim()
            }

            val cands = JSONObject(text).optJSONArray("candidates") ?: return null to "Gemini gave no answer"
            val ps = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: return null to "Gemini gave no answer"
            val sb = StringBuilder()
            for (i in 0 until ps.length()) sb.append(ps.getJSONObject(i).optString("text"))
            sb.toString() to null
        } catch (e: Exception) {
            null to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun askClaude(apiKey: String, b64: String, prompt: String): Pair<String?, String?> {
        return try {
            val content = JSONArray()
                .put(
                    JSONObject().put("type", "image").put(
                        "source",
                        JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)
                    )
                )
                .put(JSONObject().put("type", "text").put("text", prompt))
            val body = JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 200)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

            val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 45000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).optJSONObject("error")?.optString("message") ?: ""
                } catch (e: Exception) { "" }
                return null to "Claude API error $code ${msg.take(120)}".trim()
            }

            val arr = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                if (c.optString("type") == "text") sb.append(c.optString("text"))
            }
            sb.toString() to null
        } catch (e: Exception) {
            null to (e.message ?: e.javaClass.simpleName)
        }
    }
}
