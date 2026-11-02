package dz.musaedi

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Gemini {

    fun ask(key: String, model: String, system: String, user: String, json: Boolean = true): String {
        return try {
            call(key, model, system, user, json, fast = true)
        } catch (e: ThinkingUnsupported) {
            call(key, model, system, user, json, fast = false)
        }
    }

    private class ThinkingUnsupported : Exception()

    private fun call(key: String, model: String, system: String, user: String, json: Boolean, fast: Boolean): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20000
        c.readTimeout = 90000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("x-goog-api-key", key)

        val gen = JSONObject().put("temperature", 0.2)
        if (json) gen.put("responseMimeType", "application/json")
        // إيقاف «التفكير» الطويل لتسريع الرد
        if (fast) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents", JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user)))
                )
            )
            .put("generationConfig", gen)

        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val txt = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()

        if (code !in 200..299) {
            val msg = try {
                JSONObject(txt).getJSONObject("error").getString("message")
            } catch (e: Exception) {
                txt.take(200)
            }
            if (fast && code == 400 && msg.contains("thinking", true)) throw ThinkingUnsupported()
            throw Exception(friendly(code, msg))
        }

        val cands = JSONObject(txt).optJSONArray("candidates")
            ?: throw Exception("لم يرجع Gemini أي جواب")
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw Exception("جواب فارغ من Gemini")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString()
    }

    private fun friendly(code: Int, msg: String): String = when {
        code == 429 || msg.contains("quota", true) -> "وصلت للحد المجاني لـ Gemini. انتظر دقيقة ثم أعد المحاولة."
        msg.contains("API key", true) -> "مفتاح Gemini غير صحيح. صحّحه في التطبيق."
        code == 404 -> "اسم النموذج غير موجود. اكتب في خانة النموذج: gemini-flash-latest"
        else -> "خطأ $code: $msg"
    }
}
