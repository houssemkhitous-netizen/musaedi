package dz.musaedi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue

class AgentService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var root: LinearLayout? = null
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var input: EditText
    private lateinit var normalRow: LinearLayout
    private lateinit var confirmRow: LinearLayout

    @Volatile private var running = false
    @Volatile private var stopFlag = false
    @Volatile private var waitingAnswer = false
    private val answers = LinkedBlockingQueue<String>()
    private val nodes = ArrayList<AccessibilityNodeInfo>()

    // ---------------- دورة حياة الخدمة ----------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        stopFlag = true
        answers.offer("__STOP__")
        try { root?.let { wm.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------------- الواجهة العائمة ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    private fun btn(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        isAllCaps = false
        background = rounded(color, 10)
        layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), dp(6), dp(3), 0) }
        setOnClickListener { onClick() }
    }

    private fun buildOverlay() {
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        lp.x = dp(8)
        lp.y = dp(140)

        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        bubble = TextView(this).apply {
            text = "🤖"
            textSize = 26f
            gravity = Gravity.CENTER
            background = rounded(0xEE1F7A54.toInt(), 28)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        }
        attachDrag(bubble)

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(0xF2182230.toInt(), 16)
            layoutParams = LinearLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.88).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 8
            movementMethod = ScrollingMovementMethod()
            text = "أنا جاهز. اكتب ما تريد أن أفعله في هاتفك."
        }

        input = EditText(this).apply {
            hint = "مثال: افتح واتساب وأرسل لأحمد أني سأتأخر"
            setHintTextColor(0xFF8EA0B3.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            minLines = 2
            background = rounded(0xFF0F1720.toInt(), 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        normalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        normalRow.addView(btn("نفّذ", 0xFF2FB37A.toInt()) { onRun() })
        normalRow.addView(btn("إيقاف", 0xFFB3442F.toInt()) { onStop() })
        normalRow.addView(btn("تصغير", 0xFF3A4756.toInt()) { expand(false) })

        confirmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        confirmRow.addView(btn("نعم، تابع", 0xFF2FB37A.toInt()) { answers.offer("نعم، وافقت") })
        confirmRow.addView(btn("لا", 0xFFB3442F.toInt()) { answers.offer("لا، رفضت. لا تفعل ذلك") })

        panel.addView(statusView)
        panel.addView(input)
        panel.addView(confirmRow)
        panel.addView(normalRow)

        r.addView(bubble)
        r.addView(panel)
        root = r
        try { wm.addView(r, lp) } catch (_: Exception) {}
    }

    private fun attachDrag(v: View) {
        var startY = 0
        var touchY = 0f
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startY = lp.y; touchY = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - touchY).toInt()
                    if (Math.abs(dy) > dp(6)) moved = true
                    if (moved) { lp.y = (startY + dy).coerceAtLeast(0); safeUpdate() }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) expand(true); true }
                else -> false
            }
        }
    }

    private fun safeUpdate() {
        try { root?.let { wm.updateViewLayout(it, lp) } } catch (_: Exception) {}
    }

    fun expand(show: Boolean) = main.post {
        panel.visibility = if (show) View.VISIBLE else View.GONE
        bubble.visibility = if (show) View.GONE else View.VISIBLE
        lp.flags = if (show) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        safeUpdate()
        if (!show) hideKeyboard()
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        } catch (_: Exception) {}
    }

    private fun setStatus(s: String) = main.post {
        statusView.text = s
        bubble.text = if (running) "⏳" else "🤖"
    }

    private fun setConfirm(on: Boolean) = main.post {
        confirmRow.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun onRun() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        if (waitingAnswer) {
            answers.offer(t)
        } else {
            startTask(t)
        }
    }

    private fun onStop() {
        if (running) {
            stopFlag = true
            answers.offer("__STOP__")
            setStatus("⏹️ أوقفت المهمة.")
        } else {
            expand(false)
        }
    }

    // ---------------- تنفيذ المهمة ----------------

    fun startTask(goal: String) {
        if (running) {
            setStatus("هناك مهمة جارية. أوقفها أولًا.")
            expand(true)
            return
        }
        running = true
        stopFlag = false
        answers.clear()
        expand(false)
        setStatus("بدأت: $goal")
        Thread { runAgent(goal) }.start()
    }

    private fun runAgent(goal: String) {
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val key = p.getString("key", "")!!.trim()
        var model = p.getString("model", MainActivity.DEFAULT_MODEL)!!.trim().ifEmpty { MainActivity.DEFAULT_MODEL }
        if (model == "gemini-flash-latest") model = MainActivity.DEFAULT_MODEL
        val profile = p.getString("profile", "")!!
        if (key.isEmpty()) {
            running = false
            setStatus("⚠️ أضف مفتاح Gemini في تطبيق «مساعدي» أولًا.")
            expand(true)
            return
        }

        val apps = launchableApps()
        val system = Prompts.agent(profile, apps.keys.sorted())
        val hist = ArrayList<String>()
        var finished = false

        try {
            Thread.sleep(500)
            loop@ for (step in 1..30) {
                if (stopFlag) break
                val screen = dumpScreen()
                val prompt = buildString {
                    append("الهدف: ").append(goal).append("\n\nالخطوات السابقة:\n")
                    if (hist.isEmpty()) append("(لا شيء بعد)\n")
                    hist.takeLast(14).forEach { append("- ").append(it).append('\n') }
                    append("\nالتطبيق الحالي: ").append(currentPkg())
                    append("\nعناصر الشاشة الحالية:\n").append(screen)
                }
                setStatus("($step) أفكّر...")
                val raw = Gemini.ask(key, model, system, prompt)
                val obj = JSONObject(extractJson(raw))
                val thought = obj.optString("thought")
                setStatus("($step) $thought")

                // يمكن أن يرجع عدة أفعال متتالية لنفس الشاشة (مثلًا: كتابة ثم إرسال)
                val list = ArrayList<JSONObject>()
                val arr = obj.optJSONArray("actions")
                if (arr != null) {
                    for (i in 0 until minOf(arr.length(), 4)) arr.optJSONObject(i)?.let { list.add(it) }
                } else list.add(obj)

                var lastAction = ""
                for ((i, j) in list.withIndex()) {
                    if (stopFlag) break@loop
                    val action = j.optString("action")
                    lastAction = action
                    val result = execute(action, j)
                    val idPart = j.optInt("id", -1).let { if (it >= 0) " #$it" else "" }
                    val extra = listOf(j.optString("text"), j.optString("app")).filter { it.isNotBlank() }.joinToString(" ")
                    hist.add("$action$idPart $extra → $result")

                    if (action == "done") {
                        finished = true
                        setStatus("✅ " + j.optString("message", obj.optString("message", thought)))
                        expand(true)
                        break@loop
                    }
                    if (result == "STOP") break@loop
                    if (action == "confirm" || action == "ask") break
                    if (i < list.size - 1) Thread.sleep(350)
                }
                Thread.sleep(
                    when (lastAction) {
                        "open_app", "open_url" -> 1500L
                        "type" -> 300L
                        else -> 700L
                    }
                )
            }
            if (!finished && !stopFlag) {
                setStatus("توقفت بعد عدد كبير من الخطوات. أعطني أمرًا أوضح أو أكمل أنت.")
                expand(true)
            }
        } catch (e: Exception) {
            setStatus("⚠️ ${e.message}")
            expand(true)
        } finally {
            running = false
            waitingAnswer = false
            setConfirm(false)
            main.post { bubble.text = "🤖" }
            if (stopFlag) setStatus("⏹️ توقفت.")
        }
    }

    private fun execute(action: String, j: JSONObject): String {
        return when (action) {
            "click" -> {
                val n = nodeOf(j) ?: return "العنصر غير موجود"
                when {
                    clickNode(n) -> "تم النقر"
                    tap(n) -> "تم النقر بالإحداثيات"
                    else -> "فشل النقر"
                }
            }
            "long_click" -> {
                val n = nodeOf(j) ?: return "العنصر غير موجود"
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isLongClickable) c = c.parent
                if (c?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true) "تم" else "فشل"
            }
            "type" -> typeText(nodeOf(j), j.optString("text"))
            "enter" -> {
                val n = nodeOf(j) ?: findFocusInput()
                if (n != null && Build.VERSION.SDK_INT >= 30 &&
                    n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                ) "تم" else "زر Enter غير مدعوم هنا، انقر زر البحث/الإرسال"
            }
            "scroll" -> scroll(nodeOf(j), j.optString("direction", "down"))
            "back" -> { performGlobalAction(GLOBAL_ACTION_BACK); "رجوع" }
            "home" -> { performGlobalAction(GLOBAL_ACTION_HOME); "الشاشة الرئيسية" }
            "recents" -> { performGlobalAction(GLOBAL_ACTION_RECENTS); "التطبيقات الأخيرة" }
            "notifications" -> { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); "الإشعارات" }
            "open_app" -> openApp(j.optString("app"))
            "open_url" -> try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(j.optString("text")))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "فُتح الرابط"
            } catch (e: Exception) { "فشل فتح الرابط" }
            "wait" -> { Thread.sleep(1500); "انتظرت" }
            "confirm", "ask" -> askUser(j.optString("message"), action == "confirm")
            "done" -> "انتهى"
            else -> "إجراء غير معروف: $action"
        }
    }

    private fun askUser(q: String, isConfirm: Boolean): String {
        answers.clear()
        waitingAnswer = true
        setStatus((if (isConfirm) "⚠️ " else "❓ ") + q)
        setConfirm(isConfirm)
        expand(true)
        val a = answers.take()
        waitingAnswer = false
        setConfirm(false)
        expand(false)
        Thread.sleep(700)
        return if (a == "__STOP__") "STOP" else "جواب المستخدم: $a"
    }

    // ---------------- قراءة الشاشة ----------------

    private fun dumpScreen(): String {
        nodes.clear()
        val sb = StringBuilder()
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    (w.type == AccessibilityWindowInfo.TYPE_SYSTEM && w.isActive)
                ) {
                    w.root?.let { roots.add(it) }
                }
            }
        } catch (_: Exception) {}
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
        for (r in roots) walk(r, sb)
        return if (sb.isEmpty()) "(الشاشة فارغة أو لا يمكن قراءتها)" else sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo, sb: StringBuilder) {
        if (nodes.size >= 160) return
        if (!n.isVisibleToUser) return
        val txt = n.text?.toString()?.trim()?.replace('\n', ' ').orEmpty()
        val desc = n.contentDescription?.toString()?.trim()?.replace('\n', ' ').orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim().orEmpty() else ""
        val useful = txt.isNotEmpty() || desc.isNotEmpty() || n.isClickable || n.isEditable ||
                n.isScrollable || n.isCheckable
        if (useful) {
            val id = nodes.size
            nodes.add(n)
            val cls = n.className?.toString()?.substringAfterLast('.') ?: "View"
            val flags = ArrayList<String>()
            if (n.isClickable) flags.add("click")
            if (n.isEditable) flags.add("edit")
            if (n.isScrollable) flags.add("scroll")
            if (n.isCheckable) flags.add(if (n.isChecked) "checked" else "unchecked")
            if (n.isFocused) flags.add("focused")
            val r = Rect()
            n.getBoundsInScreen(r)
            sb.append('[').append(id).append("] ").append(cls)
            if (txt.isNotEmpty()) sb.append(" \"").append(txt.take(80)).append('"')
            if (desc.isNotEmpty() && desc != txt) sb.append(" (").append(desc.take(60)).append(')')
            if (hint.isNotEmpty() && txt.isEmpty()) sb.append(" hint=\"").append(hint.take(40)).append('"')
            if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append('}')
            sb.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n')
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, sb)
        }
    }

    private fun currentPkg(): String = try {
        rootInActiveWindow?.packageName?.toString() ?: "?"
    } catch (_: Exception) { "?" }

    private fun nodeOf(j: JSONObject): AccessibilityNodeInfo? {
        val id = j.optInt("id", -1)
        return if (id in nodes.indices) nodes[id] else null
    }

    private fun findFocusInput(): AccessibilityNodeInfo? = try {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    } catch (_: Exception) { null }

    // ---------------- الأفعال ----------------

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        return c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun tap(n: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 80)
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val path = Path()
        path.moveTo(x1, y1)
        if (x1 != x2 || y1 != y2) path.lineTo(x2, y2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
            .build()
        return dispatchGesture(g, null, null)
    }

    private fun typeText(target: AccessibilityNodeInfo?, text: String): String {
        var n = target ?: findFocusInput() ?: return "لا يوجد حقل كتابة"
        if (!n.isEditable) {
            clickNode(n)
            Thread.sleep(500)
            n = findFocusInput() ?: n
        }
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) "تمت الكتابة" else "فشلت الكتابة"
    }

    private fun scroll(target: AccessibilityNodeInfo?, dir: String): String {
        val up = dir == "up"
        var n: AccessibilityNodeInfo? = target
        while (n != null && !n.isScrollable) n = n.parent
        if (n == null) n = nodes.firstOrNull { it.isScrollable }
        val act = if (up) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        if (n?.performAction(act) == true) return "تم التمرير"
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val a = dm.heightPixels * 0.72f
        val b = dm.heightPixels * 0.30f
        return if (if (up) gesture(x, b, x, a, 350) else gesture(x, a, x, b, 350)) "تم السحب" else "فشل التمرير"
    }

    private fun launchableApps(): Map<String, String> {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(i, 0)
                .associate { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        } catch (_: Exception) { emptyMap() }
    }

    private fun openApp(name: String): String {
        if (name.isBlank()) return "لم يُحدَّد التطبيق"
        val apps = launchableApps()
        val label = apps.keys.firstOrNull { it.equals(name, true) }
            ?: apps.keys.firstOrNull { it.contains(name, true) }
            ?: apps.keys.firstOrNull { it.length > 2 && name.contains(it, true) }
        val pkg = label?.let { apps[it] } ?: apps.values.firstOrNull { it.contains(name, true) }
            ?: return "لم أجد تطبيقًا باسم $name"
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return "لا يمكن فتح $name"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { startActivity(intent); "فُتح ${label ?: pkg}" } catch (e: Exception) { "فشل فتح $name" }
    }

    private fun extractJson(s: String): String {
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) throw Exception("جواب غير مفهوم من Gemini")
        return s.substring(a, b + 1)
    }
}
