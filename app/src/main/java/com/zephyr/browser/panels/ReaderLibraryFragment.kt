package com.zephyr.browser.panels

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.MainActivity
import com.zephyr.browser.R
import com.zephyr.browser.db.ReaderArticleEntity
import com.zephyr.browser.db.AppDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.7.7 (user: «Reader Mode++ — офлайн-библиотека красивых статей»): the saved
 * EXTRACTED articles (reader_articles) as premium cards — accent-tinted reading
 * time badge, site + byline, date. Tap → offline reader (same premium typography
 * as the live reader); long-press → delete / open online.
 */
class ReaderLibraryFragment : Fragment() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var adapter: ArticlesAdapter? = null
    private val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // v1.7.8 (user: «что за функция библиотека статей? она пустая, даже баннера
        // который объясняет что к чему нет»): explanation banner + a proper empty
        // state instead of a blank screen.
        PanelUi.hintBanner(ctx, "reader_hint", R.drawable.ic_reader,
            ctx.getString(R.string.reader_hint_title), ctx.getString(R.string.reader_hint_body))
            ?.let { root.addView(it) }
        val recycler = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(recycler)
        // v1.7.8: friendly empty state (was a blank screen)
        emptyState = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(40), dp(24), dp(40))
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_reader)
                imageTintList = android.content.res.ColorStateList.valueOf(PanelUi.accent(ctx))
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            })
            addView(TextView(ctx).apply {
                text = getString(R.string.reader_empty_title)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(4))
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
            })
            addView(TextView(ctx).apply {
                text = getString(R.string.reader_empty_body)
                textSize = 13f
                gravity = Gravity.CENTER
                setLineSpacing(dp(2).toFloat(), 1f)
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            })
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(emptyState)
        adapter = ArticlesAdapter()
        recycler.adapter = adapter
        reload()
        return root
    }

    private fun reload() {
        val ctx = context ?: return
        io.launch {
            val items = try { AppDb.get(ctx).readerArticleDao().allOnce() } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) {
                adapter?.submit(items)
                emptyState?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private var emptyState: LinearLayout? = null

    private inner class ArticlesAdapter : RecyclerView.Adapter<ArticlesAdapter.VH>() {
        private val items = mutableListOf<ReaderArticleEntity>()
        fun submit(list: List<ReaderArticleEntity>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }
        override fun getItemCount(): Int = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(13), dp(10), dp(13))
                background = PanelUi.rounded(ctx, dp(18))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(12), dp(6), dp(12), dp(6))
                }
                isClickable = true
            }
            // icon tile (accent-filled rounded square + white reader glyph)
            val tile = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
                background = PanelUi.roundedFillAttr(ctx, dp(14))
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_reader)
                    setPadding(dp(11), dp(11), dp(11), dp(11))
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        PanelUi.themedColor(ctx, R.attr.colorOnPrimary))
                })
            }
            row.addView(tile)
            // text column
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                }
            }
            val badge = TextView(ctx).apply {
                textSize = 10.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = PanelUi.roundedFill(ctx, dp(10))
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnPrimary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val title = TextView(ctx).apply {
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 2
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
                setPadding(0, dp(4), 0, 0)
            }
            val sub = TextView(ctx).apply {
                textSize = 12.5f
                maxLines = 1
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(2), 0, 0)
            }
            col.addView(badge); col.addView(title); col.addView(sub)
            row.addView(col)
            return VH(row, badge, title, sub)
        }
        override fun onBindViewHolder(h: VH, position: Int) {
            val a = items[position]
            val ctx = h.itemView.context
            // reading-time badge: ~220 wpm from plain text length
            val words = a.html.replace(Regex("<[^>]+>"), " ").split(Regex("\\s+")).count { it.isNotBlank() }
            val minutes = (words / 220).coerceAtLeast(1)
            h.itemView.setOnClickListener { openArticle(a) }
            h.itemView.setOnLongClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(a.title)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        io.launch {
                            try { AppDb.get(ctx).readerArticleDao().delete(a) } catch (_: Exception) {}
                            withContext(Dispatchers.Main) { reload() }
                        }
                    }
                    .setNeutralButton(R.string.reader_open_online) { _, _ ->
                        if (a.url.isNotEmpty()) {
                            ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java).apply {
                                action = android.content.Intent.ACTION_VIEW
                                data = android.net.Uri.parse(a.url)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                true
            }
            h.badge.text = "${minutes} ${getString(R.string.reader_minutes)}"
            h.title.text = a.title.ifEmpty { a.site }
            h.sub.text = (a.site.ifEmpty { "—" } +
                (if (a.byline.isNotEmpty()) " · ${a.byline}" else "")) + "  ·  " + fmt.format(Date(a.savedAt))
        }
        inner class VH(v: View, val badge: TextView, val title: TextView, val sub: TextView) :
            RecyclerView.ViewHolder(v)
    }

    /** Opens the saved article in the premium offline reader (full-screen dialog). */
    private fun openArticle(a: ReaderArticleEntity) {
        val ctx = context ?: return
        val dialog = Dialog(ctx)
        dialog.window?.let { w ->
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            w.setBackgroundDrawableResource(android.R.color.black)
        }
        val root = FrameLayout(ctx).apply { setBackgroundColor(0xFF151023.toInt()) }
        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = false
            setBackgroundColor(0)
        }
        root.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE61B1230.toInt())
            setPadding(dp(6), dp(6), dp(6), dp(6))
            elevation = dp(8).toFloat()
        }
        bar.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_close)
            background = PanelUi.ripple(ctx)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFEDE7FB.toInt())
            setOnClickListener { dialog.dismiss() }
        })
        bar.addView(TextView(ctx).apply {
            text = a.title
            textSize = 13f
            maxLines = 1
            setTextColor(0xFFEDE7FB.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6); marginEnd = dp(6)
            }
        })
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        dialog.setContentView(root)
        dialog.show()
        val page = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body{background:#151023;color:#EDE7FB;font-family:Georgia,'Times New Roman',serif;
                   margin:0 auto;padding:56px 18px 40px;max-width:720px;font-size:17px;line-height:1.62;}
              h1{font-size:1.5em;line-height:1.3;margin:0 0 6px;color:#FFFFFF;}
              .meta{color:#B7A7DA;font-size:0.8em;margin-bottom:24px;}
              .meta b{color:#9F7BEA;}
              img{max-width:100%;height:auto;border-radius:8px;margin:10px 0;}
              a{color:#9F7BEA;text-decoration:none;}
              p{margin:0 0 1em;text-align:justify;}
              blockquote{border-left:3px solid #6E5A96;margin:1em 0;padding:2px 14px;color:#B7A7DA;}
              pre{background:#241A3D;border-radius:8px;padding:12px;overflow-x:auto;font-size:0.85em;}
              h2,h3{color:#FFFFFF;line-height:1.35;}
            </style></head><body>
            <h1>${android.text.TextUtils.htmlEncode(a.title)}</h1>
            <div class="meta">${android.text.TextUtils.htmlEncode(a.site)}${if (a.byline.isNotEmpty()) " · <b>${android.text.TextUtils.htmlEncode(a.byline)}</b>" else ""}</div>
            ${a.html}
            </body></html>
        """.trimIndent()
        wv.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
