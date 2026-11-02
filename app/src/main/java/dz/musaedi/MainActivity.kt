package dz.musaedi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
    }

    private lateinit var state: TextView
    private lateinit var keyIn: EditText
    private lateinit var modelIn: EditText
    private lateinit var profileIn: EditText
    private lateinit var goalIn: EditText

    private val bg = 0xFF0F1720.toInt()
    private val card = 0xFF18222E.toInt()
    private val acc = 0xFF2FB37A.toInt()
    private val mut = 0xFF8EA0B3.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(20), dp(16), dp(40))
        }

        col.addView(text("🤖 مساعدي", 26f, Color.WHITE, bold = true))
        col.addView(text("مساعد يتحكم في هاتفك مكانك: يفتح التطبيقات، ينقر، يكتب، ويبحث — فقط عندما تأمره.", 14f, mut))

        state = text("", 15f, Color.WHITE, bold = true).apply {
            background = rounded(card); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        col.addView(space(14)); col.addView(state)

        // 1) المفتاح
        col.addView(section("1. مفتاح Gemini (مجاني)"))
        keyIn = input("الصق المفتاح هنا  AIza...", single = true)
        col.addView(keyIn)
        col.addView(button("احصل على مفتاح مجاني", 0xFF3A4756.toInt()) {
            open("https://aistudio.google.com/apikey")
        })
        modelIn = input(DEFAULT_MODEL, single = true)
        col.addView(small("النموذج (اتركه كما هو، يختار التطبيق المتاح تلقائيًا):"))
        col.addView(modelIn)

        // 2) معلوماتي
        col.addView(section("2. معلوماتي"))
        col.addView(small("الاسم، الهاتف، البريد، المدينة، العنوان... يستعملها المساعد لملء الاستمارات والكتابة باسمك. لا تضع كلمات السرّ أو أرقام البطاقات."))
        profileIn = input("الاسم: ...\nالهاتف: ...\nالمدينة: ...", single = false)
        col.addView(profileIn)
        col.addView(button("حفظ", acc) { save(); toast("تم الحفظ ✓") })

        // 3) التفعيل
        col.addView(section("3. تفعيل المساعد"))
        col.addView(small("افتح «إمكانية الوصول» ← التطبيقات المثبتة ← مساعدي ← فعّل.\nإذا كان الزر رماديًا أو ظهرت رسالة «إعداد محظور»: اضغط الزر الثاني، ثم ⋮ أعلى الشاشة ← «السماح بالإعدادات المحظورة»، ثم ارجع وفعّله."))
        col.addView(button("فتح إعدادات إمكانية الوصول", acc) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("معلومات التطبيق (للإعدادات المحظورة)", 0xFF3A4756.toInt()) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        // 4) الأمر
        col.addView(section("4. أعطه أمرًا"))
        col.addView(small("أو اضغط الزر العائم 🤖 فوق أي تطبيق."))
        goalIn = input("مثال: ابحث في Ouedkniss عن هاتف Redmi Note 13 وقل لي أرخص 3 أسعار", single = false)
        col.addView(goalIn)
        col.addView(button("نفّذ ▶", acc) { runGoal() })

        col.addView(space(16))
        col.addView(small("الأمان: يطلب موافقتك قبل إرسال أي رسالة أو دفع أو حذف، ولا يكتب كلمات السرّ أو رموز التحقق أبدًا. يمكنك إيقافه في أي لحظة من الزر العائم."))

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(col) }
        setContentView(scroll)

        val p = prefs()
        keyIn.setText(p.getString("key", ""))
        profileIn.setText(p.getString("profile", ""))
    }

    override fun onResume() {
        super.onResume()
        modelIn.setText(prefs().getString("model", DEFAULT_MODEL).let { if (it.isNullOrBlank() || it == "gemini-2.5-flash") DEFAULT_MODEL else it })
        if (AgentService.instance != null) {
            state.text = "✅ المساعد مفعّل — ستجد الزر 🤖 فوق كل التطبيقات"
            state.setTextColor(acc)
        } else {
            state.text = "❌ المساعد غير مفعّل بعد (الخطوة 3)"
            state.setTextColor(0xFFFF8A80.toInt())
        }
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun prefs() = getSharedPreferences("cfg", Context.MODE_PRIVATE)

    private fun save() {
        prefs().edit()
            .putString("key", keyIn.text.toString().trim())
            .putString("model", modelIn.text.toString().trim().ifEmpty { DEFAULT_MODEL })
            .putString("profile", profileIn.text.toString().trim())
            .apply()
    }

    private fun runGoal() {
        save()
        val goal = goalIn.text.toString().trim()
        if (goal.isEmpty()) { toast("اكتب الأمر أولًا"); return }
        if (keyIn.text.isBlank()) { toast("أضف مفتاح Gemini أولًا"); return }
        val svc = AgentService.instance
        if (svc == null) { toast("فعّل المساعد أولًا (الخطوة 3)"); return }
        goalIn.setText("")
        moveTaskToBack(true)
        svc.startTask(goal)
    }

    // ---------- أدوات الواجهة ----------

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.25f)
    }

    private fun section(s: String) = text(s, 17f, Color.WHITE, bold = true).apply {
        setPadding(0, dp(22), 0, dp(6))
    }

    private fun small(s: String) = text(s, 13f, mut).apply { setPadding(0, dp(2), 0, dp(6)) }

    private fun space(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun input(hintText: String, single: Boolean) = EditText(this).apply {
        hint = hintText
        setHintTextColor(0xFF5D6D7E.toInt())
        setTextColor(Color.WHITE)
        textSize = 15f
        background = rounded(card)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        gravity = Gravity.TOP or Gravity.START
        if (single) {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        background = rounded(color)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun open(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
