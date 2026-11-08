package app.lockmein

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Calm warning banner displayed as a TYPE_ACCESSIBILITY_OVERLAY window.
 * Does NOT block touch input (FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL),
 * allowing the user to scroll away or switch apps during the 5-second window.
 */
class FilterOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    val isShowing: Boolean get() = view != null

    fun show(message: String = "Sexual or revealing content detected. Scroll away or switch now.") {
        if (view != null) return

        val density = context.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))

            // Calm dark surface card with warm accent border
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1B1F")) // Material 3 surface
                setStroke(dp(1), Color.parseColor("#E06A3B")) // Palette.Attention
                cornerRadius = density * 12f
            }

            // Layout params with margins
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(dp(16), 0, dp(16), 0)
            }
            layoutParams = lp

            // Warning Icon
            val icon = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_dialog_alert)
                setColorFilter(Color.parseColor("#E06A3B"))
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    rightMargin = dp(12)
                }
            }
            addView(icon)

            // Text column
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)

                val title = TextView(context).apply {
                    text = "Screen Filter"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                }
                addView(title)

                val body = TextView(context).apply {
                    text = message
                    setTextColor(Color.parseColor("#CAC4D0"))
                    textSize = 12f
                }
                addView(body)
            }
            addView(textCol)
        }

        // Window parameters: non-blocking, top-pinned banner
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            y = dp(40) // Below status bar
        }

        runCatching {
            windowManager.addView(root, params)
            view = root
        }
    }

    fun dismiss() {
        val current = view ?: return
        runCatching {
            windowManager.removeView(current)
        }
        view = null
    }
}
