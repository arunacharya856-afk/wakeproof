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

/** Asks Claude whether the photo shows a made bed. */
object BedChecker {
    const val MODEL = "claude-haiku-5-5"
    const val GEMINI_MODEL = "gemini-2.5-flash"

    sealed class Result {
        object Made : Result()
        data class NotMade(val reason: String) : Result()
        data class Failed(val message: String) : Result()
    }

    private const val PROMPT =
        "This photo comes from a morning alarm app: the user must make their bed and photograph it " +
        "to switch the alarm off. Is this a real, live photo of a bed that has been made " +
        "(covers pulled up and roughly straightened, pillows near the head)? Be fair, not strict — " +
        "dorm-level neat is enough. Say not made if there is no bed, the covers are clearly messy or " +
        "thrown back, or it looks like a photo of a screen or printed picture. " +
        "Reply with ONLY JSON: {\"made\": true or false, \"reason\": \"under 15 words\"}"

    fun loadBitmap(ctx: Context, uri: Uri, maxSide: Int = 1600): Bitmap? {
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

    /** Picks the AI from the key: Google keys start with "AIza", Claude keys with "sk-ant-". */
    fun providerName(key: String): String = if (isGemini(key)) "Gemini" else "Claude"

    private fun isGemini(key: String) = key.trim().startsWith("AIza")

    fun check(apiKey: String, bmp: Bitmap): Result {
        val b64 = try {
            val maxSide = 1024
            val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
            val img = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            } else bmp
            val bos = ByteArrayOutputStream()
            img.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            return Result.Failed("Couldn't read the photo")
        }
        return if (isGemini(apiKey)) checkGemini(apiKey.trim(), b64) else checkClaude(apiKey, b64)
    }

    private fun parseVerdict(out: String, who: String): Result {
        val s = out.indexOf('{')
        val e = out.lastIndexOf('}')
        if (s < 0 || e <= s) return Result.Failed("Couldn't read $who's answer")
        val j = JSONObject(out.substring(s, e + 1))
        return if (j.optBoolean("made", false)) Result.Made
        else Result.NotMade(j.optString("reason", "Bed doesn't look made yet"))
    }

    private fun checkGemini(apiKey: String, b64: String): Result {
        return try {
            val parts = JSONArray()
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
                .put(JSONObject().put("text", PROMPT))
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
                return Result.Failed("Gemini error $code$hint ${msg.take(100)}".trim())
            }

            val cands = JSONObject(text).optJSONArray("candidates")
                ?: return Result.Failed("Gemini gave no answer")
            val ps = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: return Result.Failed("Gemini gave no answer")
            val sb = StringBuilder()
            for (i in 0 until ps.length()) sb.append(ps.getJSONObject(i).optString("text"))
            parseVerdict(sb.toString(), "Gemini")
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun checkClaude(apiKey: String, b64: String): Result {
        return try {
            val content = JSONArray()
                .put(
                    JSONObject().put("type", "image").put(
                        "source",
                        JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)
                    )
                )
                .put(JSONObject().put("type", "text").put("text", PROMPT))
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
            conn.setRequestProperty("x-api-key", apiKey.trim())
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
                return Result.Failed("Claude API error $code ${msg.take(120)}".trim())
            }

            val arr = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                if (c.optString("type") == "text") sb.append(c.optString("text"))
            }
            parseVerdict(sb.toString(), "Claude")
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
