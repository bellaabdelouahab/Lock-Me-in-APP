package app.lockmein

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shown on the home screen right after the filter closed an app (spec v3.1 F3): "Closed for 30 s" and a small "Wrong block?".
 * Tapping it shows the second step, "Save for review". Neither step reopens anything: the block has already happened and
 * completes as usual. Goes away on its own after [SHOW_MS].
 */
class BlockCard(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    val isShowing: Boolean get() = view != null

    /** [onSave] runs on the main thread and answers the line to show (saved, or why not). */
    fun show(appLabel: String, onSave: () -> String) {
        dismiss()
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val title = TextView(context).apply {
            text = "$appLabel closed for 30 s"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        val body = TextView(context).apply {
            text = "The screen filter saw sexual or revealing content."
            setTextColor(Color.parseColor("#CAC4D0"))
            textSize = 12f
        }
        fun button(label: String) = TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#CAC4D0"))
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val wrong = button("Wrong block?")
        val save = button("Save for review").apply { visibility = View.GONE; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) }
        val cancel = button("Cancel").apply { visibility = View.GONE }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(cancel); addView(wrong); addView(save)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1B1F"))
                setStroke(dp(1), Color.parseColor("#49454F"))
                cornerRadius = density * 12f
            }
            addView(title); addView(body); addView(row)
        }

        wrong.setOnClickListener {
            wrong.visibility = View.GONE
            save.visibility = View.VISIBLE
            cancel.visibility = View.VISIBLE
            body.text = "Saves this screen, encrypted on the phone, for the review at 19:30. The app stays closed."
            view?.removeCallbacks(autoDismiss)
            view?.postDelayed(autoDismiss, SHOW_MS)
        }
        cancel.setOnClickListener { dismiss() }
        save.setOnClickListener {
            body.text = onSave()
            save.visibility = View.GONE
            cancel.visibility = View.GONE
            view?.removeCallbacks(autoDismiss)
            view?.postDelayed(autoDismiss, 3_000)
        }

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            gravity = Gravity.BOTTOM or Gravity.FILL_HORIZONTAL
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            y = dp(96)
        }
        val frame = android.widget.FrameLayout(context).apply { setPadding(dp(16), 0, dp(16), 0); addView(root) }
        runCatching {
            windowManager.addView(frame, params)
            view = frame
            frame.postDelayed(autoDismiss, SHOW_MS)
        }
    }

    private val autoDismiss = Runnable { dismiss() }

    fun dismiss() {
        val current = view ?: return
        current.removeCallbacks(autoDismiss)
        runCatching { windowManager.removeView(current) }
        view = null
    }

    companion object {
        const val SHOW_MS = 15_000L
    }
}
