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
 * Shown on the home screen right after the filter closed an app: "closed for 30 s", and whether the screen was saved for review.
 * Saving happens on the warning itself ("Wrong?", FilterOverlay), where the screen is still in front of him. Goes away on its own
 * after [SHOW_MS].
 */
class BlockCard(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    val isShowing: Boolean get() = view != null

    fun show(appLabel: String, note: String) {
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
            text = note
            setTextColor(Color.parseColor("#CAC4D0"))
            textSize = 12f
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1B1F"))
                setStroke(dp(1), Color.parseColor("#49454F"))
                cornerRadius = density * 12f
            }
            addView(title); addView(body)
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
        const val SHOW_MS = 6_000L
    }
}
