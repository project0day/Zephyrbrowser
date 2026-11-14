package com.zephyr.browser.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.R
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.DomainSettingsEntity
import com.zephyr.browser.panels.PanelUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Per-site settings list: add domain, edit JS/cookies/images/popups/desktop overrides. */
class SitePrefsFragment : Fragment() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var items: List<DomainSettingsEntity> = emptyList()
    private lateinit var col: LinearLayout

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.set_site)
        col = SettingsUi.screen(ctx)
        root.addView(SettingsUi.scrollOf(col), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
        return root
    }

    private fun refresh() {
        val ctx = context ?: return
        io.launch {
            items = try { AppDb.get(ctx.applicationContext).domainSettingsDao().allOnce() } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) { rebuild() }
        }
    }

    private fun rebuild() {
        val ctx = context ?: return
        col.removeAllViews()
        col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_add, getString(R.string.site_add_domain)) {
            val input = PanelUi.dialogField(ctx, getString(R.string.site_domain_hint))
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.site_add_domain)
                .setView(PanelUi.fieldWrap(input))
                .setPositiveButton(R.string.save) { _, _ ->
                    val host = input.text.toString().trim()
                        .removePrefix("https://").removePrefix("http://").removePrefix("www.").trim('/')
                    if (host.contains('.') && !host.contains(' ')) {
                        io.launch {
                            try { AppDb.get(ctx.applicationContext).domainSettingsDao().insert(DomainSettingsEntity(domain = host)) } catch (_: Exception) {}
                            withContext(Dispatchers.Main) { refresh() }
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })
        if (items.isEmpty()) {
            col.addView(PanelUi.emptyView(ctx, R.string.empty_list))
        }
        for (d in items) {
            col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_website, d.domain, null) {
                editDialog(d)
            })
        }
    }

    private fun editDialog(d: DomainSettingsEntity) {
        val ctx = context ?: return
        val names = arrayOf(
            getString(R.string.site_js), getString(R.string.site_cookies), getString(R.string.site_images),
            getString(R.string.site_popups), getString(R.string.site_desktop))
        val states = arrayOf(d.javaScript, d.cookies, d.images, d.popups, d.desktop)
        val values = names.mapIndexed { i, n ->
            n + ": " + when (states[i]) {
                null -> getString(R.string.site_not_set)
                true -> getString(R.string.site_allow)
                false -> getString(R.string.site_block)
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(d.domain)
            .setItems(values) { _, which ->
                val next = when (states[which]) {
                    null -> true
                    true -> false
                    false -> null
                }
                val updated = when (which) {
                    0 -> d.copy(javaScript = next)
                    1 -> d.copy(cookies = next)
                    2 -> d.copy(images = next)
                    3 -> d.copy(popups = next)
                    else -> d.copy(desktop = next)
                }
                io.launch {
                    try { AppDb.get(ctx.applicationContext).domainSettingsDao().insert(updated) } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { refresh() }
                }
            }
            .setNeutralButton(R.string.delete) { _, _ ->
                io.launch {
                    try { AppDb.get(ctx.applicationContext).domainSettingsDao().delete(d) } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { refresh() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
