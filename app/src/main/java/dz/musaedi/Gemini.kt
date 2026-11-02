package dz.musaedi

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Gemini {

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    /** آخر نموذج اشتغل فعلًا — يحفظه التطبيق ليستعمله في المرات القادمة */
    @Volatile
    var workingModel: String? = null

    /** طرق إيقاف «التفكير» الطويل، تُجرَّب بالترتيب حسب ما يقبله النموذج */
    private val thinkingModes = listOf<JSONObject?>(
        JSONObject().put("thinkingBudget", 0),
        JSONObject().put("thinkingLevel", "low"),
        null
    )

    @Volatile
    private var thinkingIndex = 0

    private class BadRequest(val code: Int, msg: String) : Exception(msg)

    fun ask(key: String, model: String, system: String, user: String, json: Boolean = true): String {
        var m = workingModel ?: model
        var discovered = false
        while (true) {
            try {
                val out = call(key, m, system, user, json)
                workingModel = m
                return out
            } catch (e: BadRequest) {
                val msg = e.message ?: ""
                val modelGone = e.code == 404 || msg.contains("not found", true) ||
                        msg.contains("no longer available", true) || msg.contains("not supported", true) ||
                        msg.contains("new users", true) || msg.contains("deprecated", true)
                val thinkingIssue = e.code == 400 && (msg.contains("thinking", true) || msg.contains("budget", true))
                when {
                    thinkingIssue && thinkingIndex < thinkingModes.size - 1 -> thinkingIndex++
                    modelGone && !discovered -> {
                        discovered = true
                        thinkingIndex = 0
                        m = discoverModel(key) ?: throw Exception("لم أجد نموذج Gemini متاحًا في حسابك.")
                    }
                    else -> throw Exception(friendly(e.code, msg))
                }
            }
        }
    }

    private fun call(key: String, model: String, system: String, user: String, json: Boolean): String {
        val c = URL("$BASE/models/$model:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20000
        c.readTimeout = 90000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("x-goog-api-key", key)

        val gen = JSONObject().put("temperature", 0.2)
        if (json) gen.put("responseMimeType", "application/json")
        thinkingModes[thinkingIndex]?.let { gen.put("thinkingConfig", JSONObject(it.toString())) }

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
        val txt = read(c, code)
        if (code !in 200..299) throw BadRequest(code, errMsg(txt))

        val cands = JSONObject(txt).optJSONArray("candidates")
            ?: throw Exception("لم يرجع Gemini أي جواب")
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw Exception("جواب فارغ من Gemini")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        return sb.toString()
    }

    /** يسأل Google عن النماذج المتاحة لهذا المفتاح ويختار أسرع نموذج Flash مناسب */
    fun discoverModel(key: String): String? {
        val c = URL("$BASE/models?pageSize=200").openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.setRequestProperty("x-goog-api-key", key)
        val code = c.responseCode
        val txt = read(c, code)
        if (code !in 200..299) throw Exception(friendly(code, errMsg(txt)))

        val arr = JSONObject(txt).optJSONArray("models") ?: return null
        val bad = listOf("image", "tts", "live", "audio", "embed", "robotics", "omni", "veo", "imagen", "lyria", "aqa", "learnlm", "gemma", "computer")
        val names = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val methods = o.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            val name = o.optString("name").removePrefix("models/")
            if (!methods.contains("generateContent")) continue
            if (!name.startsWith("gemini")) continue
            if (bad.any { name.contains(it, true) }) continue
            names.add(name)
        }
        if (names.isEmpty()) return null
        if ("gemini-flash-latest" in names) return "gemini-flash-latest"

        fun version(n: String): Double =
            Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

        val flash = names.filter { it.contains("flash") }
        val pool = when {
            flash.any { !it.contains("lite") && !it.contains("preview") && !it.contains("exp") } ->
                flash.filter { !it.contains("lite") && !it.contains("preview") && !it.contains("exp") }
            flash.any { !it.contains("preview") && !it.contains("exp") } ->
                flash.filter { !it.contains("preview") && !it.contains("exp") }
            flash.isNotEmpty() -> flash
            else -> names
        }
        return pool.sortedWith(compareByDescending<String> { version(it) }.thenBy { it.length }).first()
    }

    private fun read(c: HttpURLConnection, code: Int): String {
        val s = if (code in 200..299) c.inputStream else c.errorStream
        val t = s?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        return t
    }

    private fun errMsg(txt: String): String = try {
        JSONObject(txt).getJSONObject("error").getString("message")
    } catch (e: Exception) {
        txt.take(200)
    }

    private fun friendly(code: Int, msg: String): String = when {
        code == 429 || msg.contains("quota", true) -> "وصلت للحد المجاني لـ Gemini. انتظر دقيقة ثم أعد المحاولة."
        msg.contains("API key", true) -> "مفتاح Gemini غير صحيح. صحّحه في التطبيق."
        msg.contains("location", true) || msg.contains("region", true) -> "Gemini غير متاح من منطقتك بهذا المفتاح."
        else -> "خطأ $code: $msg"
    }
}
