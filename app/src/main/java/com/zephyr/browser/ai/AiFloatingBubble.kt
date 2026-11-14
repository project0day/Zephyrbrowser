package com.zephyr.browser.ai

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import com.zephyr.browser.MainActivity
import com.zephyr.browser.R
import com.zephyr.browser.ui.DragCloseTarget

/**
 * v1.7.25b: the minimized AI Assistant — a draggable in-app bubble.
 *
 * Closing the chat no longer throws the conversation away: the sheet collapses
 * into this bubble, the dialogue stays in [AiChatStore], and one tap brings the
 * whole thing back exactly where it was. Drag to move (it snaps to the nearest
 * edge), long-press for a small menu.
 *
 * It is a plain child of the activity's content view — NO «draw over other apps»
 * permission is involved, so nothing to grant and nothing to leak.
 */
object AiFloatingBubble {

    private const val SIZE_DP = 52

    private var view: View? = null
    private var target: com.zephyr.browser.ui.DragCloseTarget? = null
    // remembered between shows so the bubble reappears where the user parked it
    private var lastX = -1f
    private var lastY = -1f

    fun isShown(): Boolean = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(activity: MainActivity, unread: Int = 0) {
        hide(activity)
        val ctx = activity
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val d = { v: Int -> AiUi.dp(ctx, v) }
        val size = d(SIZE_DP)

        val bubble = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_ai)
            setPadding(d(13), d(13), d(13), d(13))
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(AiUi.ACCENT, AiUi.ACCENT_2)
            ).apply { shape = GradientDrawable.OVAL }
            // v1.7.26 (user: «у кнопки ИИ взади некрасивая квадратная тень»):
            // the default outline provider traces the VIEW BOUNDS, so the
            // elevation shadow under a circular button was a square. Trace the
            // oval explicitly and clip to it.
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            // v1.7.38: no elevation — the user sees the square shadow, not the glow
            elevation = 0f
            layoutParams = FrameLayout.LayoutParams(size, size)
        }

        val badge = TextView(ctx).apply {
            text = if (unread > 9) "9+" else unread.toString()
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFEF4444.toInt())
            }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            elevation = 0f
            visibility = if (unread > 0) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(d(18), d(18), Gravity.TOP or Gravity.END)
        }

        val holder = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(size + d(6), size + d(6))
            addView(bubble)
            addView(badge)
        }

        val margin = d(12)
        val screenW = ctx.resources.displayMetrics.widthPixels
        val screenH = ctx.resources.displayMetrics.heightPixels
        holder.x = if (lastX >= 0) lastX else (screenW - size - margin).toFloat()
        holder.y = if (lastY >= 0) lastY else (screenH * 0.62f)

        var downX = 0f; var downY = 0f
        var startX = 0f; var startY = 0f
        var dragging = false
        var downAt = 0L

        // v1.7.28 (user: «сделай плавающей кнопке возможность закрыть её
        // перетаскиванием в центр снизу, похожая схема как у плавающего
        // плеера»): the drop target appears the moment a drag starts, arms when
        // the bubble hovers over it, and swallows the bubble on release.
        val closeTarget = DragCloseTarget.attach(ctx, content, bottomMarginDp = 34)
        target = closeTarget

        fun overTarget(v: View): Boolean {
            val c = closeTarget.discCenter() ?: return false
            val loc = IntArray(2)
            try { v.getLocationOnScreen(loc) } catch (_: Exception) { return false }
            val bx = loc[0] + v.width / 2f
            val by = loc[1] + v.height / 2f
            val dx = bx - c[0]
            val dy = by - c[1]
            return kotlin.math.sqrt(dx * dx + dy * dy) < closeTarget.catchRadius()
        }

        holder.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = v.x; startY = v.y
                    dragging = false
                    downAt = e.eventTime
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (kotlin.math.abs(dx) > d(8) || kotlin.math.abs(dy) > d(8))) {
                        dragging = true
                        closeTarget.show()
                    }
                    if (dragging) {
                        v.x = (startX + dx).coerceIn(0f, (screenW - v.width).toFloat())
                        v.y = (startY + dy).coerceIn(d(56).toFloat(), (screenH - v.height - d(12)).toFloat())
                        closeTarget.setArmed(overTarget(v))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (dragging) {
                        val kill = e.actionMasked == MotionEvent.ACTION_UP && overTarget(v)
                        closeTarget.setArmed(false)
                        closeTarget.hide()
                        if (kill) {
                            // suck the bubble into the target, then drop it
                            val c = closeTarget.discCenter()
                            val anim = v.animate().alpha(0f).scaleX(0.35f).scaleY(0.35f).setDuration(200)
                            if (c != null) {
                                val loc = IntArray(2)
                                try { v.getLocationOnScreen(loc) } catch (_: Exception) {}
                                anim.x(v.x + (c[0] - (loc[0] + v.width / 2f)))
                                    .y(v.y + (c[1] - (loc[1] + v.height / 2f)))
                            }
                            anim.withEndAction { hide(activity) }.start()
                        } else {
                            // snap to the closest edge
                            val edge = if (v.x + v.width / 2f < screenW / 2f) margin.toFloat()
                            else (screenW - v.width - margin).toFloat()
                            v.animate().x(edge).setDuration(180).withEndAction {
                                lastX = v.x; lastY = v.y
                            }.start()
                        }
                    } else if (e.actionMasked == MotionEvent.ACTION_UP &&
                        e.eventTime - downAt < 400
                    ) {
                        lastX = v.x; lastY = v.y
                        hide(activity)
                        AiAssistantSheet.reopen(activity)
                    }
                    true
                }
                else -> false
            }
        }

        holder.setOnLongClickListener { v ->
            val menu = PopupMenu(ctx, v)
            menu.menu.add(0, 1, 0, ctx.getString(R.string.ai_bubble_open))
            menu.menu.add(0, 2, 1, ctx.getString(R.string.ai_new_chat))
            menu.menu.add(0, 3, 2, ctx.getString(R.string.ai_bubble_hide))
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { hide(activity); AiAssistantSheet.reopen(activity) }
                    2 -> { hide(activity); AiAssistantSheet.reopenNew(activity) }
                    else -> hide(activity)
                }
                true
            }
            menu.show()
            true
        }

        content.addView(holder)
        view = holder
        holder.alpha = 0f
        holder.scaleX = 0.6f; holder.scaleY = 0.6f
        holder.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            // v1.7.26: a short pulse when an answer landed while minimized, so
            // the badge is not the only thing announcing it
            if (unread > 0) {
                bubble.animate().scaleX(1.18f).scaleY(1.18f).setDuration(220).withEndAction {
                    bubble.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
                }.start()
            }
        }.start()
    }

    fun hide(activity: MainActivity) {
        try { target?.detach() } catch (_: Exception) {}
        target = null
        val v = view ?: return
        view = null
        try { (v.parent as? ViewGroup)?.removeView(v) } catch (_: Exception) {}
    }
}
