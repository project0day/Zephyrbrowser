package com.zephyr.browser.panels

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.zephyr.browser.MainActivity
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.db.OfflinePageEntity
import com.zephyr.browser.Urls
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.7.6 (user: «Оффлайн-страницы — кнопка "сохранить для чтения без сети"»):
 * the list of pages snapshotted by MainActivity.saveOffline(). Rows open the
 * saved web-archive in a new tab (offline — no network needed); long-press offers
 * "open online" (the original URL) and delete (archive file + row).
 */
class OfflinePanelFragment : Fragment() {

    private var items: List<OfflinePageEntity> = emptyList()
    private lateinit var list: LinearLayout

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        // v1.7.8: dismissable explanation banner (was a bare empty screen)
        PanelUi.hintBanner(ctx, "offline_hint", R.drawable.ic_download,
            ctx.getString(R.string.offline_hint_title), ctx.getString(R.string.offline_hint_body))
            ?.let { root.addView(it) }
        val scroll = ScrollView(ctx)
        list = PanelUi.listColumn(ctx)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reload()
    }

    private fun reload() {
        val ctx = context ?: return
        list.removeAllViews()
        items = try {
            kotlinx.coroutines.runBlocking {
                com.zephyr.browser.db.AppDb.get(ctx.applicationContext).offlinePageDao().allOnce()
            }
        } catch (_: Exception) { emptyList() }

        if (items.isEmpty()) {
            list.addView(PanelUi.emptyState(ctx, R.drawable.ic_download,
                R.string.empty_offline_title, R.string.empty_offline_hint))
            return
        }
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        for (p in items) {
            val subtitle = Urls.domainOf(p.url).ifEmpty { p.url } + " · " + fmt.format(Date(p.savedAt))
            list.addView(PanelUi.entryRow(
                ctx,
                R.drawable.ic_reader,
                p.title.ifEmpty { p.url },
                subtitle,
                onClick = { openOffline(p) },
                onLongClick = { entryMenu(p) }
            ))
        }
    }

    private fun openOffline(p: OfflinePageEntity) {
        val ctx = context ?: return
        if (!File(p.path).exists()) {
            Toast.makeText(ctx, R.string.offline_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_ACTION, "offline")
            putExtra("path", p.path)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        requireActivity().finish()
    }

    private fun entryMenu(p: OfflinePageEntity) {
        val ctx = context ?: return
        val options = arrayOf(
            ctx.getString(R.string.offline_open_online),
            ctx.getString(R.string.delete))
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(p.title.ifEmpty { p.url })
            .setItems(options) { d, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(ctx, MainActivity::class.java).apply {
                            putExtra(MainActivity.EXTRA_ACTION, "offline")
                            putExtra("url", p.url)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                        requireActivity().finish()
                    }
                    1 -> {
                        try { File(p.path).delete() } catch (_: Exception) {}
                        kotlinx.coroutines.runBlocking {
                            try {
                                com.zephyr.browser.db.AppDb.get(ctx.applicationContext)
                                    .offlinePageDao().delete(p)
                            } catch (_: Exception) {}
                        }
                        reload()
                    }
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
