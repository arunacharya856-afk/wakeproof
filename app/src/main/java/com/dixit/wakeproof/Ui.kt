package com.dixit.wakeproof

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Small helpers for building screens in code (no extra libraries needed). */
object Ui {
    const val BG = 0xFF0F1216.toInt()
    const val CARD = 0xFF1A1F26.toInt()
    const val TEXT = 0xFFF2F4F7.toInt()
    const val MUTED = 0xFF9AA4B2.toInt()
    const val ACCENT = 0xFFFF8A3D.toInt()
    const val GOOD = 0xFF3DD68C.toInt()
    const val BAD = 0xFFFF5A5A.toInt()
    const val DARK_TEXT = 0xFF111418.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun text(ctx: Context, s: String, sp: Float = 16f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = sp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }

    fun button(ctx: Context, label: String, color: Int = ACCENT, textColor: Int = DARK_TEXT, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(color)
            }
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 18).toFloat()
            setColor(CARD)
        }
        val p = dp(ctx, 18)
        setPadding(p, p, p, p)
    }

    fun lp(ctx: Context, top: Int = 12): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, top) }
}
