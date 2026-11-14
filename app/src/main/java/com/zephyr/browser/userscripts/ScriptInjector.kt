package com.zephyr.browser.userscripts

import android.content.Context
import com.zephyr.browser.Urls
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.ScriptEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Userscript injection orchestrator (the piece that was missing in v1.7.1:
 * scripts were stored in Room but NEVER executed).
 *
 *  - Async cache: enabled scripts are read from Room once (IO dispatcher) into
 *    a volatile list; [refresh] re-reads it after every panel edit.
 *  - [ensureLoaded] kicks the first read off and is called from
 *    MainActivity.onPageStarted (covers app cold start) and from the scripts
 *    panel — it is idempotent and cheap.
 *  - [injectFor] builds ONE combined JavaScript string for a URL + run-at
 *    phase; MainActivity evaluates it via WebView.evaluateJavascript.
 *
 *  Scripts never run on non-http(s) pages (about:home, about:blank, data:),
 *  so internal pages and the Zephyr home are excluded by construction.
 *  Incognito tabs are skipped by the caller (MainActivity).
 *
 *  GM API surface (Tampermonkey-compatible subset): GM_addStyle,
 *  GM_getValue / GM_setValue / GM_deleteValue / GM_listValues backed by
 *  localStorage under a per-script key prefix, GM_log, GM_xmlhttpRequest
 *  (minimal, page-context fetch — limited by CORS; cross-origin requests
 *  only succeed for servers that send permissive CORS headers, which is an
 *  accepted v1 limitation), GM.info / GM_info and unsafeWindow.
 *  Each script is wrapped in its own IIFE inside try/catch so one broken
 *  script can never kill the others.
 */
object ScriptInjector {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var appContext: Context? = null
    @Volatile private var scripts: List<ScriptEntity>? = null   // null = not loaded yet
    @Volatile private var loadStarted = false

    /** Idempotent: starts the async DB read on first call. Safe from any thread. */
    fun ensureLoaded(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        if (scripts == null && !loadStarted) refresh()
    }

    /** Re-read enabled scripts from Room (call after any create/update/delete/toggle). */
    fun refresh() {
        val ctx = appContext ?: return
        loadStarted = true
        scope.launch {
            val list = try {
                AppDb.get(ctx).scriptDao().enabled()
            } catch (_: Exception) {
                emptyList()
            }
            scripts = list
        }
    }

    /**
     * Combined JS for all enabled scripts that match [url] and declare this
     * [runAt] phase (document-start, document-end or document-idle). Empty
     * string when nothing applies — the caller skips the evaluateJavascript
     * round-trip entirely.
     */
    fun injectFor(url: String, runAt: String): String {
        if (!Urls.isHttp(url)) return ""            // never on internal pages
        val list = scripts ?: return ""             // cache not warm yet
        if (list.isEmpty()) return ""
        val parts = ArrayList<String>(list.size)
        for (s in list) {
            try {
                if (!s.enabled) continue                 // defensive: cache is loaded from enabled() only
                val meta = UserScriptMeta.parse(s.code)
                if (meta.runAt != runAt) continue
                if (!meta.matchesUrl(url)) continue
                if (!UserScriptMeta.domainsAllow(s.domains, url)) continue
                parts.add(wrapForExecution(s, meta))
            } catch (_: Exception) {
                // one broken script must never kill the others
            }
        }
        if (parts.isEmpty()) return ""
        return parts.joinToString("\n")
    }

    /** Stable per-script identity for GM storage keys: hash of name + code. */
    private fun scriptId(s: ScriptEntity): String =
        "z" + Integer.toHexString((s.name + "|" + s.code).hashCode())

    private fun wrapForExecution(s: ScriptEntity, meta: UserScriptMeta): String {
        val id = scriptId(s)
        val nameJson = JSONObject.quote(if (meta.name.isNotBlank()) meta.name else s.name)
        val ver = if (meta.version.isNotBlank()) meta.version else "1.0"
        val body = UserScriptMeta.stripHeader(s.code).trim()
        val head = GM_HEAD
            .replace("%ZSID%", id)
            .replace("%ZNAMEJSON%", nameJson)
            .replace("%ZVER%", ver)
        return head + "\n" + body + "\n" + GM_FOOT
    }

    // GM sandbox prologue. Placeholders: %ZSID%, %ZNAMEJSON%, %ZVER% — the user
    // code itself is appended AFTER these replacements, so user text can never
    // collide with them. No dollar signs in this literal (Kotlin raw strings
    // interpolate dollar signs).
    private const val GM_HEAD = """
(function(){
"use strict";
var __zsid="%ZSID%";
var __zp="zgm:"+__zsid+":";
var GM={
 info:{script:{name:%ZNAMEJSON%,version:"%ZVER%"},scriptHandler:"Zephyr",scriptHandlerVersion:"1.7.2"},
 addStyle:function(css){try{var s=document.createElement("style");s.textContent=String(css);(document.head||document.documentElement).appendChild(s);return s;}catch(e){return null;}},
 getValue:function(k,d){try{var r=localStorage.getItem(__zp+k);if(r===null)return d;try{return JSON.parse(r);}catch(e){return d;}}catch(e){return d;}},
 setValue:function(k,v){try{localStorage.setItem(__zp+k,JSON.stringify(v===undefined?null:v));}catch(e){}},
 deleteValue:function(k){try{localStorage.removeItem(__zp+k);}catch(e){}},
 listValues:function(){var a=[];try{for(var i=0;i<localStorage.length;i++){var key=localStorage.key(i);if(key&&key.indexOf(__zp)===0)a.push(key.substring(__zp.length));}}catch(e){}return a;},
 log:function(){try{var a=["[ZephyrUS]"].concat(Array.prototype.slice.call(arguments));console.log.apply(console,a);}catch(e){}}
};
GM.xmlHttpRequest=function(details){
 try{
  if(!details||!details.url)return;
  var m=(details.method||"GET").toUpperCase();
  var o={method:m,headers:details.headers||{}};
  if(m!=="GET"&&m!=="HEAD"&&details.data!=null){o.body=typeof details.data==="string"?details.data:String(details.data);}
  fetch(details.url,o).then(function(r){
   r.text().then(function(t){
    try{if(details.onload)details.onload({status:r.status,readyState:4,responseText:t,responseURL:details.url,finalUrl:details.url});}catch(e){}
   });
  }).catch(function(err){try{if(details.onerror)details.onerror({error:err&&err.message?err.message:String(err)});}catch(e){}});
 }catch(e){}
};
var GM_addStyle=GM.addStyle,GM_getValue=GM.getValue,GM_setValue=GM.setValue,
    GM_deleteValue=GM.deleteValue,GM_listValues=GM.listValues,GM_log=GM.log,
    GM_xmlhttpRequest=GM.xmlHttpRequest,GM_info=GM.info,unsafeWindow=window;
try{
"""

    private const val GM_FOOT = """
}catch(__ze){try{console.warn("[ZephyrUS]",__ze);}catch(__e2){}}
})();
"""
}
