package com.zephyr.browser.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.zephyr.browser.R

/** Omnibox dropdown suggestions: search / history / bookmark rows. */
class SuggestionsAdapter(private val ctx: Context) : RecyclerView.Adapter<SuggestionsAdapter.Holder>() {

    data class Suggestion(val favicon: Any?, val url: String, val title: String, val kind: String)

    var onPick: ((Suggestion) -> Unit)? = null
    private val items = mutableListOf<Suggestion>()

    fun submit(list: List<Suggestion>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class Holder(val row: View, val icon: ImageView, val title: TextView, val url: TextView) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            isClickable = true
            foreground = ripple()
        }
        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ctx.dp(12)
            }
        }
        val title = TextView(ctx).apply {
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        val url = TextView(ctx).apply {
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        textCol.addView(title)
        textCol.addView(url)
        row.addView(icon)
        row.addView(textCol)
        return Holder(row, icon, title, url)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: Holder, position: Int) {
        val s = items[position]
        h.title.text = s.title.ifEmpty { s.url }
        h.url.text = when (s.kind) {
            "search" -> ""
            "history" -> s.url
            "bookmark" -> s.url
            else -> s.url
        }
        h.url.visibility = if (h.url.text.isEmpty()) View.GONE else View.VISIBLE
        val tint = themed(R.attr.colorOnSurface)
        when (s.kind) {
            "search" -> { h.icon.setImageResource(R.drawable.ic_search); h.icon.imageTintList = cs(themed(R.attr.colorPrimary)) }
            "history" -> { h.icon.setImageResource(R.drawable.ic_history); h.icon.imageTintList = cs(tint) }
            "bookmark" -> { h.icon.setImageResource(R.drawable.ic_bookmark); h.icon.imageTintList = cs(tint) }
        }
        h.title.setTextColor(themed(R.attr.colorOnSurface))
        h.url.setTextColor(themed(R.attr.colorOnSurfaceVariant))
        h.row.setOnClickListener { onPick?.invoke(s) }
    }

    private fun cs(c: Int) = android.content.res.ColorStateList.valueOf(c)

    private fun themed(attr: Int): Int = try {
        val ta = ctx.theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, Color.DKGRAY)
        ta.recycle()
        c
    } catch (_: Exception) { Color.DKGRAY }

    private fun ripple(): android.graphics.drawable.Drawable? = try {
        val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val d = ta.getDrawable(0)
        ta.recycle()
        d
    } catch (_: Exception) { null }

    private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
