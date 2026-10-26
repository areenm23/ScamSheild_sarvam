package live.scamshield.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import live.scamshield.MainActivity
import live.scamshield.R
import live.scamshield.state.Alert
import live.scamshield.state.Risk
import live.scamshield.state.UiState
import kotlin.math.abs

/**
 * Floating shield (TYPE_APPLICATION_OVERLAY, needs the user's "display over other apps" permission).
 * It is small, not focusable, sits at the top edge away from the call controls, can be dragged,
 * and never intercepts touches outside its own bounds — the user can always end or operate the call.
 */
class OverlayController(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val dp = context.resources.displayMetrics.density
    private var root: LinearLayout? = null
    private lateinit var bubble: ImageView
    private lateinit var card: LinearLayout
    private lateinit var title: TextView
    private lateinit var phrase: TextView
    private lateinit var warning: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private var level = Risk.LOW

    fun show() {
        if (root != null) return
        bubble = ImageView(context).apply {
            setImageResource(R.drawable.ic_shield)
            setPadding(px(14), px(14), px(14), px(14))
            background = circle(GREEN)
            elevation = px(6).toFloat()
        }
        title = text(15f, bold = true)
        phrase = text(13f).apply { setTextColor(Color.parseColor("#FDE68A")) }
        warning = text(14f)
        val note = text(11f).apply { setTextColor(Color.parseColor("#8AA0AD")); setText("A warning, not proof. You stay in control of the call.") }
        val explain = button("View Explanation") {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_SCROLL_TO_ANALYSIS, true))
        }
        val dismiss = button("Dismiss") { card.visibility = View.GONE }
        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(12))
            background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(Color.parseColor("#F20C1016")); setStroke(px(1), RED) }
            visibility = View.GONE
            addView(title); addView(phrase); addView(warning); addView(note)
            addView(LinearLayout(context).apply { addView(explain); addView(dismiss) })
            layoutParams = LinearLayout.LayoutParams(px(290), LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8) }
        }
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(card)
            addView(FrameLayout(context).apply { addView(bubble, FrameLayout.LayoutParams(px(56), px(56))) })
        }
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = px(12); y = px(96) }
        enableDragAndTap()
        wm.addView(root, lp)
        root!!.apply { alpha = 0f; scaleX = .6f; scaleY = .6f; animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator()).setDuration(350).start() }
    }

    fun render(state: UiState) {
        if (root == null || state.risk == level) return
        level = state.risk
        bubble.background = circle(when (level) { Risk.HIGH -> RED; Risk.MEDIUM -> AMBER; Risk.LOW -> GREEN })
        if (level == Risk.HIGH) bubble.animate().scaleX(1.15f).scaleY(1.15f).setDuration(180).withEndAction {
            bubble.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
        }.start()
    }

    fun showAlert(alert: Alert): Unit? {
        if (root == null) return null
        title.text = if (alert.level == Risk.HIGH) "⚠ Strong scam indicators" else "Caution: suspicious language"
        phrase.text = alert.phrase?.let { "“$it”" } ?: ""
        phrase.visibility = if (alert.phrase.isNullOrBlank()) View.GONE else View.VISIBLE
        warning.text = alert.warning
        (card.background as GradientDrawable).setStroke(px(1), if (alert.level == Risk.HIGH) RED else AMBER)
        if (card.visibility != View.VISIBLE) {
            card.visibility = View.VISIBLE
            card.translationY = -px(12).toFloat(); card.alpha = 0f
            card.animate().translationY(0f).alpha(1f).setDuration(250).start()
        }
        return Unit
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDragAndTap() {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX - (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt()
                    root?.let { wm.updateViewLayout(it, lp) }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawX - downX) < px(8) && abs(e.rawY - downY) < px(8)) {
                        card.visibility = if (card.visibility == View.VISIBLE || level == Risk.LOW) View.GONE else View.VISIBLE
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun text(size: Float, bold: Boolean = false) = TextView(context).apply {
        textSize = size; setTextColor(Color.parseColor("#E6F1F7"))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, px(2), 0, px(2))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = px(10).toFloat(); setColor(Color.parseColor("#0F1B25")) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(38)).apply { marginEnd = px(8); topMargin = px(8) }
        setPadding(px(12), 0, px(12), 0)
    }

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun px(v: Int) = (v * dp).toInt()

    private companion object {
        val GREEN = Color.parseColor("#10B981")
        val AMBER = Color.parseColor("#F59E0B")
        val RED = Color.parseColor("#EF4444")
    }
}
