package com.zephyr.browser

import org.json.JSONObject

/** Injected JavaScript payloads. Kotlin string templates: ${...} is Kotlin interpolation — used deliberately for JSON quoting. */
object Scripts {

    /**
     * v1.6.0 CRITICAL FIX: this script used to do `window.Zephyr = api`, which
     * REPLACED the native JavascriptInterface object with a plain JS wrapper that
     * called ITSELF (infinite recursion, swallowed by catch) — every bridge call
     * (save password / login autofill / payment / identity) silently died.
     * The wrapper is gone: watchers now call the native `window.Zephyr` directly.
     * What remains here is ONLY the Notification permission shim.
     */
    const val BRIDGE = """
        (function(){
          try{
            if(window.__zephyrBridgeDone) return; window.__zephyrBridgeDone=true;
            if(!window.Notification){
              try{
                window.Notification = function(){};
                window.Notification.permission = 'denied';
                window.Notification.requestPermission = function(){ return Promise.resolve('denied'); };
              }catch(e){}
            }
          }catch(e){}
        })();
    """


    /**
     * v1.7.7: VIDEO SNIFFER (user: «скачивание видео с сайтов — HLS/mp4-сниффер +
     * кнопка в плеере»). Hooks everything that can hand a real media URL to a
     * player — the HTMLMediaElement.src setter, <source src=...> attributes, and
     * fetch/XHR traffic carrying .m3u8/.mp4/.webm — and reports each find to the
     * native bridge (MainActivity.onMediaFound). A MutationObserver catches
     * player <video> elements added later by SPA players. Duplicate reports are
     * deduplicated natively.
     *
     * v1.7.9 (user: «Найденные видео — опция хорошая, но ничего не парсится, не
     * находит!»): modern players (YouTube first of all) stream via MSE with
     * blob: URLs, so src/fetch hooks alone see nothing. Added:
     *  - ytInitialPlayerResponse.streamingData walk — plain .url entries from
     *    formats + adaptiveFormats (signatureCipher-only entries are skipped:
     *    deciphering needs the page's own player JS) and .hlsManifestUrl;
     *  - <a href> anchor scan (many sites hand the real file via a plain link);
     *  - performance.getEntriesByType('resource') scan — media loaded BEFORE
     *    the hooks were installed still shows up in the resource timing;
     *  - re-scan loop: a 2s interval (up to ~60s or until 8 finds) + a
     *    throttled MutationObserver + the 'yt-navigate-finish' SPA event all
     *    re-run the video scan AND the YouTube walk.
     * Everything is wrapped in try/catch — a broken hook must never kill the
     * page. (No Kotlin interpolation: the JS never uses a dollar sign.)
     * v1.7.14: the media regex was widened to .mov/.mkv/.flv (movie sites
     * hand out these extensions as direct links; native parseSniffed maps
     * them now too).
     * v1.7.15: cross-origin iframe PLAYER sources — an embeds() helper reports
     * every http(s) iframe src via onEmbedFound (own seenE dedupe set; srcs
     * that already match the media regex are left to report()). Native shows
     * «Плеер · host» rows that open the player page in a new tab, where the
     * sniffer then catches the direct media URLs.
     */
    const val MEDIA_SNIFFER = """
        (function(){
          try{
            if(window.__zephyrMediaHooked) return; window.__zephyrMediaHooked=true;
            var seen={}, seenE={}, hits=0;
            function report(u){
              try{
                if(!u||typeof u!=='string') return false;
                if(!/^https?:/.test(u) && u.indexOf('data:')!==0) return false;
                if(!/\.(m3u8|mp4|webm|m4v|mov|mkv|flv|mpd)(\?|#|$)/i.test(u) && u.indexOf('videoplayback')<0) return false;
                if(seen[u]) return false;
                seen[u]=1;
                window.Zephyr.onMediaFound(String(u));
                hits++;
                return true;
              }catch(e){}
              return false;
            }
            var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');
            if(d&&d.set){
              Object.defineProperty(HTMLMediaElement.prototype,'src',{
                set:function(v){ try{report(v);}catch(e){} return d.set.call(this,v); },
                get:function(){ return d.get.call(this); },
                configurable:true
              });
            }
            var sa = Element.prototype.setAttribute;
            Element.prototype.setAttribute=function(n,v){
              try{
                var ln=(n||'').toLowerCase();
                if(ln==='src'&&(this.tagName==='SOURCE'||this.tagName==='VIDEO'))
                  report(v);
              }catch(e){}
              return sa.apply(this,arguments);
            };
            var of = window.fetch;
            if(of){
              window.fetch=function(u,o){
                try{ var s=(u&&u.url)?u.url:u; if(typeof s==='string') report(s); }catch(e){}
                return of.apply(this,arguments);
              };
            }
            var ox = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open=function(m,u){
              try{ if(typeof u==='string') report(u); }catch(e){}
              return ox.apply(this,arguments);
            };
            function scan(){
              try{
                var vs=document.querySelectorAll('video');
                for(var i=0;i<vs.length;i++){
                  var v=vs[i];
                  if(v.src) report(v.src);
                  if(v.currentSrc) report(v.currentSrc);
                  var srcs=v.querySelectorAll('source');
                  for(var j=0;j<srcs.length;j++) if(srcs[j].src) report(srcs[j].src);
                }
              }catch(e){}
            }
            // v1.7.9: plain download links — <a href="....mp4">
            function anchors(){
              try{
                var as=document.querySelectorAll('a[href]');
                for(var i=0;i<as.length;i++){
                  try{ var h=as[i].href; if(h) report(h); }catch(e2){}
                }
              }catch(e){}
            }
            // v1.7.9: resource-timing entries — media fetched before hooking
            function perf(){
              try{
                if(!window.performance||!performance.getEntriesByType) return;
                var es=performance.getEntriesByType('resource');
                for(var i=0;i<es.length;i++){
                  try{ if(es[i]&&es[i].name) report(es[i].name); }catch(e2){}
                }
              }catch(e){}
            }
            // v1.7.9: YouTube — MSE players never touch video.src; the direct
            // googlevideo URLs live in ytInitialPlayerResponse.streamingData.
            // Plain .url entries only (signatureCipher needs the page's own JS),
            // plus hlsManifestUrl when present.
            function ytWalk(){
              try{
                var pr=window.ytInitialPlayerResponse;
                if(!pr||!pr.streamingData) return;
                var arr=[];
                if(pr.streamingData.formats&&pr.streamingData.formats.length)
                  arr=arr.concat(pr.streamingData.formats);
                if(pr.streamingData.adaptiveFormats&&pr.streamingData.adaptiveFormats.length)
                  arr=arr.concat(pr.streamingData.adaptiveFormats);
                for(var i=0;i<arr.length;i++){
                  try{
                    var f=arr[i];
                    if(f&&typeof f.url==='string'&&f.url.indexOf('http')===0) report(f.url);
                  }catch(e2){}
                }
                if(typeof pr.streamingData.hlsManifestUrl==='string')
                  report(pr.streamingData.hlsManifestUrl);
              }catch(e){}
            }
            // v1.7.15: cross-origin iframe players — the DOM walk cannot enter
            // them, but their src IS the player page. Reported via onEmbedFound
            // with a separate dedupe set; plain media srcs stay with report().
            function embeds(){
              try{
                var fs=document.querySelectorAll('iframe');
                for(var i=0;i<fs.length;i++){
                  try{
                    var src=fs[i].src;
                    if(!src) src=fs[i].getAttribute('src')||'';
                    if(!src) continue;
                    try{ src=new URL(src,location.href).href; }catch(e0){}
                    if(src.indexOf('http')!==0) continue;
                    if(src.indexOf('about:blank')===0) continue;
                    if(/\.(m3u8|mp4|webm|m4v|mov|mkv|flv|mpd)(\?|#|$)/i.test(src)||src.indexOf('videoplayback')>=0) continue;
                    if(seenE[src]) continue;
                    seenE[src]=1;
                    window.Zephyr.onEmbedFound(String(src));
                  }catch(e2){}
                }
              }catch(e){}
            }
            function rescan(){
              try{ scan(); }catch(e){}
              try{ anchors(); }catch(e){}
              try{ perf(); }catch(e){}
              try{ ytWalk(); }catch(e){}
              try{ embeds(); }catch(e){}
            }
            rescan();
            try{
              var lastMut=0;
              new MutationObserver(function(){
                try{
                  var now=Date.now();
                  if(now-lastMut<500) return;
                  lastMut=now;
                  try{ scan(); }catch(e1){}
                  try{ ytWalk(); }catch(e2){}
                }catch(e){}
              }).observe(document.documentElement,
                {childList:true,subtree:true});
            }catch(e){}
            // v1.7.9: SPA players swap the media long after load — follow
            // YouTube's own navigation event as well
            try{
              window.addEventListener('yt-navigate-finish',function(){
                try{ seen={}; }catch(e){}
                try{ rescan(); }catch(e){}
                try{ setTimeout(function(){ try{ rescan(); }catch(e){} },1200); }catch(e){}
              });
            }catch(e){}
            // v1.7.9: keep looking every 2s for up to ~60s (or until 8 finds) —
            // the player may resolve its URLs lazily, seconds after page load
            var ticks=0;
            var timer=setInterval(function(){
              try{
                ticks++;
                rescan();
                if(ticks>=30||hits>=8){ try{ clearInterval(timer); }catch(e){} }
              }catch(e){ try{ clearInterval(timer); }catch(e2){} }
            },2000);
          }catch(e){}
        })();
    """

    /**
     * v1.7.14 (user: «⋮ → Видео — пусто на киносайтах»): ONE-SHOT on-demand
     * collector. showVideoSheet() injects this every time the sheet opens —
     * independent of the videoSniffer pref, which only governs the always-on
     * passive hooks. No hooks, no install guard, no timers: it walks the DOM
     * (including same-origin iframes), plain <a> links, the resource-timing
     * table and ytInitialPlayerResponse ONCE and reports every candidate to
     * the native bridge (MainActivity dedupes). Idempotent and safe to re-run.
     * Everything is wrapped in try/catch. (No Kotlin interpolation.)
     * v1.7.15: cross-origin iframe PLAYER sources are collected too —
     * movie sites embed players from other origins and the DOM walk cannot
     * enter them, so every http(s) iframe src is reported via onEmbedFound
     * (native shows «Плеер · host» rows that open the player in a new tab).
     */
    const val MEDIA_PROBE = """
        (function(){
          try{
            var RE=/\.(m3u8|mp4|webm|m4v|mov|mkv|flv|mpd)(\?|#|$)/i;
            function take(u){
              try{
                if(!u||typeof u!=='string') return;
                if(u.indexOf('blob:')===0||u.indexOf('data:')===0) return;
                try{ u=new URL(u,location.href).href; }catch(e){}
                if(!/^https?:/.test(u)) return;
                if(!RE.test(u)&&u.indexOf('videoplayback')<0) return;
                try{ window.Zephyr.onMediaFound(String(u)); }catch(e2){}
              }catch(e){}
            }
            try{
              var els=document.querySelectorAll('video,source,audio');
              for(var i=0;i<els.length;i++){
                try{ take(els[i].src); take(els[i].currentSrc); }catch(e1){}
              }
            }catch(e){}
            // same-origin iframes — every access may throw cross-origin
            try{
              var fr=document.querySelectorAll('iframe');
              for(var j=0;j<fr.length;j++){
                try{
                  var doc=fr[j].contentDocument; if(!doc) continue;
                  var fe=doc.querySelectorAll('video,source');
                  for(var k=0;k<fe.length;k++){
                    try{ take(fe[k].src); take(fe[k].currentSrc); }catch(e2){}
                  }
                }catch(e1){}
              }
            }catch(e){}
            // v1.7.15: CROSS-ORIGIN iframe players — the DOM walk above cannot
            // enter them, but their src IS the player page. Report each one via
            // onEmbedFound; native offers opening it in a new tab where the
            // sniffer catches the direct media URLs. Skip srcs that are already
            // plain media links (take() reported those above).
            try{
              var fe2=document.querySelectorAll('iframe');
              for(var r=0;r<fe2.length;r++){
                try{
                  var src=fe2[r].src;
                  if(!src) src=fe2[r].getAttribute('src')||'';
                  if(!src) continue;
                  try{ src=new URL(src,location.href).href; }catch(e3){}
                  if(src.indexOf('http')!==0) continue;
                  if(src.indexOf('about:blank')===0) continue;
                  if(RE.test(src)||src.indexOf('videoplayback')>=0) continue;
                  window.Zephyr.onEmbedFound(String(src));
                }catch(e1){}
              }
            }catch(e){}
            // plain download links — <a href="....mp4">
            try{
              var as=document.querySelectorAll('a[href]');
              for(var m=0;m<as.length;m++){ try{ take(as[m].href); }catch(e1){} }
            }catch(e){}
            // resource-timing entries — media fetched before we ever looked
            try{
              if(window.performance&&performance.getEntriesByType){
                var es=performance.getEntriesByType('resource');
                for(var n=0;n<es.length;n++){
                  try{ if(es[n]&&es[n].name) take(es[n].name); }catch(e1){}
                }
              }
            }catch(e){}
            // YouTube — MSE players never touch video.src; the direct
            // googlevideo URLs live in ytInitialPlayerResponse.streamingData
            try{
              var pr=window.ytInitialPlayerResponse;
              if(pr&&pr.streamingData){
                var arr=[];
                if(pr.streamingData.formats) arr=arr.concat(pr.streamingData.formats);
                if(pr.streamingData.adaptiveFormats) arr=arr.concat(pr.streamingData.adaptiveFormats);
                for(var q=0;q<arr.length;q++){
                  try{
                    var f=arr[q];
                    if(f&&typeof f.url==='string'&&f.url.indexOf('http')===0) take(f.url);
                  }catch(e1){}
                }
                if(typeof pr.streamingData.hlsManifestUrl==='string')
                  take(pr.streamingData.hlsManifestUrl);
              }
            }catch(e){}
          }catch(e){}
        })();
    """

    /**
     * v1.7.7: INLINE PAGE TRANSLATOR (user: «автоперевод всей страницы инъекционно,
     * как Chrome, без обёртки»). v1.7.10 REWORK (user: «Перевод плохо работает и
     * вообще переводит пару слов и все!») — the old collector skipped every
     * element that had children (any <p>Hello <b>world</b></p> never reached
     * translation at all) and native fired one HTTP request per string, so a
     * normal page (100-500 text nodes) took minutes and the user saw only the
     * first few words change.
     *
     * New design (paired with TranslationApi.translateBatch on the native side):
     *  - a TreeWalker collects individual TEXT NODES (subtrees of SCRIPT/STYLE/
     *    NOSCRIPT/TEXTAREA/CODE/PRE are skipped), so inline markup survives —
     *    only .data of text nodes is swapped, never innerHTML;
     *  - nodes are GROUPED under their nearest block ancestor (P,H1..H6,LI,TD,TH,
     *    FIGCAPTION,BLOCKQUOTE,DT,DD,LABEL,OPTION,ARTICLE,SECTION; fallback =
     *    direct parent element, never BODY). One group per block, chunked at
     *    ≤1500 chars / ≤48 nodes; hidden blocks and pure number/punctuation
     *    groups are skipped;
     *  - groups are batched into bridge calls of ~1400 total chars (NOT
     *    per-string): each call sends the array of group texts (group text =
     *    its node texts joined with \n) via window.Zephyr.translateChunk(json,
     *    cbId); native runs them through TranslationApi.translateBatch (one
     *    HTTP round-trip per ~1600 chars) and answers __zephyrTrCb(cbId, translations);
     *  - per group: the answer is split by \n; if the part count matches the
     *    node count, each part becomes that node's .data (original leading/
     *    trailing whitespace re-attached, internal whitespace normalized). On
     *    ANY mismatch the group is left untouched — a batch can never corrupt
     *    page text;
     *  - after every completed batch: optional native hook
     *    window.Zephyr.translationProgress(done,total) (v1.7.10 % toast);
     *    translationFinished() still fires once when the last batch lands.
     *    __zephyrTrRestore() puts back the ORIGINAL .data of every collected
     *    node — translated or not.
     */
    const val PAGE_TRANSLATOR = """
        (function(){
          try{
            if(window.__zephyrTranslator) return; window.__zephyrTranslator=true;
            var groups = [];
            var MAXG = 1500, MAXN = 48, BATCH = 1400;
            function norm(s){ return String(s).replace(/\s+/g, ' '); }
            function tagOf(e){ try{ return String(e.tagName).toUpperCase(); }catch(x){ return ''; } }
            function skipUp(n){
              var e = n.parentElement;
              while(e){
                var t = tagOf(e);
                if(t=='SCRIPT'||t=='STYLE'||t=='NOSCRIPT'||t=='TEXTAREA'||t=='CODE'||t=='PRE') return true;
                e = e.parentElement;
              }
              return false;
            }
            function blockOf(n){
              var e = n.parentElement;
              while(e && e!==document.body){
                var t = tagOf(e);
                if(t=='P'||t=='H1'||t=='H2'||t=='H3'||t=='H4'||t=='H5'||t=='H6'||t=='LI'||
                   t=='TD'||t=='TH'||t=='FIGCAPTION'||t=='BLOCKQUOTE'||t=='DT'||t=='DD'||
                   t=='LABEL'||t=='OPTION'||t=='ARTICLE'||t=='SECTION') return e;
                e = e.parentElement;
              }
              return n.parentElement;
            }
            function collect(){
              groups = [];
              try{
                var byBlock = new WeakMap();
                var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                var n;
                while((n = w.nextNode())){
                  if(skipUp(n)) continue;
                  var raw = n.data || '';
                  var core = norm(raw).trim();
                  if(core.length < 2 || core.length > 1500) continue;
                  var b = blockOf(n);
                  if(!b) continue;
                  var g = byBlock.get(b);
                  if(!g || g.nodes.length >= MAXN || g.len + core.length + 1 > MAXG){
                    g = {block:b, nodes:[], origs:[], leads:[], trails:[], cores:[], len:0, text:''};
                    byBlock.set(b, g);
                    groups.push(g);
                  }
                  g.nodes.push(n);
                  g.origs.push(raw);
                  g.leads.push((raw.match(/^\s+/) || [''])[0]);
                  g.trails.push((raw.match(/\s+$/) || [''])[0]);
                  g.cores.push(core);
                  g.len += core.length + 1;
                }
              }catch(e){}
              var keep = [];
              for(var i=0;i<groups.length;i++){
                var g = groups[i];
                g.text = g.cores.join("\n");
                try{ if(g.block.offsetParent===null && g.block.getClientRects().length===0) continue; }catch(e){}
                if(/^[0-9\s\.,:;\-–—%$€₽£§]+$/.test(g.text)) continue;
                keep.push(g);
              }
              groups = keep;
            }
            window.__zephyrTranslate = function(target){
              collect();
              if(!groups.length){ try{window.Zephyr.translationFinished();}catch(e){} return; }
              var batches = [], cur = [], curLen = 0;
              for(var i=0;i<groups.length;i++){
                var t = groups[i].text;
                if(cur.length && curLen + t.length + 1 > BATCH){ batches.push(cur); cur=[]; curLen=0; }
                cur.push(i); curLen += t.length + 1;
              }
              if(cur.length) batches.push(cur);
              var done = 0;
              window.__zephyrTrCb = function(cbId, translations){
                try{
                  var idxs = batches[cbId] || [];
                  for(var k=0;k<idxs.length && k<translations.length;k++){
                    var g = groups[idxs[k]];
                    if(!g || !translations[k]) continue;
                    var parts = String(translations[k]).split("\n");
                    if(parts.length !== g.nodes.length) continue;   // safety: group stays as-is
                    for(var j=0;j<g.nodes.length;j++){
                      var pt = norm(parts[j]).trim();
                      if(!pt) pt = g.cores[j];                      // empty answer → keep original
                      try{ g.nodes[j].data = g.leads[j] + pt + g.trails[j]; }catch(e){}
                    }
                  }
                }catch(e){}
                done++;
                try{window.Zephyr.translationProgress&&window.Zephyr.translationProgress(done,batches.length);}catch(e){}
                if(done>=batches.length){ try{window.Zephyr.translationFinished();}catch(e){} }
              };
              window.__zephyrTrRestore = function(){
                for(var i=0;i<groups.length;i++){
                  var g = groups[i];
                  for(var j=0;j<g.nodes.length;j++){
                    try{ g.nodes[j].data = g.origs[j]; }catch(e){}
                  }
                }
                groups = [];
                window.__zephyrTrCb = function(){};   // drop results of in-flight batches
              };
              for(var c=0;c<batches.length;c++){
                var arr = [];
                for(var j=0;j<batches[c].length;j++) arr.push(groups[batches[c][j]].text);
                try{ window.Zephyr.translateChunk(JSON.stringify(arr), c); }catch(e){}
              }
            };
          }catch(e){}
        })();
    """

    /**
     * Login form detector → bridge (v1.4.1: much stronger heuristics).
     * Catches: classic form submit, button-click logins (no <form> submit), Enter key,
     * SPA renderings via MutationObserver, and picks the username by scoring
     * (autocomplete > type=email/tel > name/id hints > first visible text field).
     */
    const val FORM_WATCHER = """
        (function(){
          try{
            if(window.__zephyrFormWatch) return; window.__zephyrFormWatch=true;
            var reported = false;
            function vis(el){ try{ return el.offsetParent!==null || el.getClientRects().length; }catch(e){ return false; } }
            function pwFields(root){
              root = root || document;
              var list = Array.prototype.slice.call(root.querySelectorAll('input[type=password]'));
              return list.filter(function(el){ return vis(el); });
            }
            function scoreUser(el){
              var s = 0, t = ((el.type||'')+' '+(el.name||'')+' '+(el.id||'')+' '+(el.autocomplete||'')+' '+(el.placeholder||'')+' '+(el.className||'')).toLowerCase();
              if((el.autocomplete||'').toLowerCase().indexOf('username')>=0) s+=100;
              if(el.type==='email') s+=50;
              if(t.indexOf('mail')>=0) s+=20;
              if(t.indexOf('user')>=0||t.indexOf('login')>=0||t.indexOf('account')>=0) s+=15;
              if(el.type==='tel'||t.indexOf('phone')>=0||t.indexOf('tel')>=0) s+=10;
              if(el.type==='text'||el.type===''||el.type==='undefined') s+=5;
              return s;
            }
            function userFieldFor(pw){
              // nearest form first
              var scope = pw.form || (pw.closest && pw.closest('form')) || document;
              var cands = Array.prototype.slice.call(scope.querySelectorAll('input,select')).filter(function(el){
                var t = el.type || '';
                if(t==='password'||t==='checkbox'||t==='radio'||t==='file'||t==='hidden'||t==='button'||t==='submit') return false;
                return vis(el);
              });
              // only fields ABOVE/BEFORE the password make sense for logins
              var best=null, bestScore=-1;
              for(var i=0;i<cands.length;i++){
                var el=cands[i];
                if(el===pw) continue;
                var s = scoreUser(el);
                if(s>bestScore){ bestScore=s; best=el; }
              }
              if(!best || bestScore<=0){
                // fall back: any visible text-ish input in the document near the password
                var all = Array.prototype.slice.call(document.querySelectorAll('input')).filter(function(el){
                  var t=el.type||'';
                  return (t==='text'||t==='email'||t==='tel'||t==='') && vis(el);
                });
                best = all.length ? all[0] : null;
              }
              return best;
            }
            function report(root){
              var pws = pwFields(root);
              if(!pws.length) return;
              var pw = pws[0];
              if(!pw.value) return;
              var uf = userFieldFor(pw);
              var u = uf && uf.value ? uf.value : '';
              // don't double-report the same credentials
              var key = u + '\u0001' + pw.value;
              if(window.__zephyrLastRep === key) return;
              window.__zephyrLastRep = key;
              reported = true;
              try{ if(window.Zephyr && window.Zephyr.onLoginSubmitted) window.Zephyr.onLoginSubmitted(String(u||''), String(pw.value)); }catch(e){}
            }
            document.addEventListener('submit', function(e){ try{ report(e.target); }catch(e){} }, true);
            // v1.6.0: report BEFORE the navigation kills pending timers — 'submit' is the
            // primary path; button-click + Enter are fallbacks for JS-driven logins.
            document.addEventListener('click', function(e){
              try{
                var b = e.target && e.target.closest ? e.target.closest('button,input[type=submit],[role=button],a[class*=login],a[class*=btn]') : null;
                if(!b) return;
                setTimeout(function(){ report(document); }, 350);
              }catch(e){}
            }, true);
            // v1.6.0: BLUR on a filled password field = the classic "user typed creds and
            // moved on / the site logged them in via XHR without any submit event"
            // (GitHub SPA-style) moment — report then too.
            document.addEventListener('focusout', function(e){
              try{
                var el = e.target;
                if(!el || el.type !== 'password' || !el.value) return;
                setTimeout(function(){ report(document); }, 120);
              }catch(err){}
            }, true);
            // Enter key inside a password field
            document.addEventListener('keydown', function(e){
              try{
                if(e.keyCode===13 && e.target && e.target.type==='password') setTimeout(function(){ report(document); }, 250);
              }catch(e){}
            }, true);
            // v1.4.2 FIX 7: FOCUS on a password field → offer saved-login autofill.
            // v1.7.1 FIX 2: the SAME offer now also fires when the user focuses the
            // LOGIN/EMAIL field (user report: the popup appeared only on the password
            // field). Username detection is deliberately conservative — explicit signals
            // only (autocomplete / type=email / name|id hints / a text-ish field inside a
            // form that also has a password, i.e. GitHub's name="login" pattern) — and
            // search-ish fields are excluded up front. Guards: one offer per field per
            // page lifetime (data-zephyr-user-offer) + ONE shared 4s throttle for both
            // triggers (window.__zephyrLoginFocusAt) so password+username never
            // double-fire; the native side gates the fill behind the device lock.
            function isUsernameField(el){
              try{
                if(!el || el.tagName!=='INPUT') return false;
                var t = el.type || '';
                if(t==='password'||t==='search'||t==='checkbox'||t==='radio'||t==='file'||t==='hidden'||t==='submit'||t==='button'||t==='image'||t==='reset'||t==='number') return false;
                if((el.getAttribute('role')||'')==='searchbox') return false;
                var ac = (el.getAttribute('autocomplete')||'').toLowerCase();
                var ni = ((el.name||'')+' '+(el.id||'')).toLowerCase();
                // search-ish fields are never logins ('search' substring; bare 'q'/'s' exact ids)
                if(ni.indexOf('search')>=0) return false;
                if((el.name||'').toLowerCase()==='q' || (el.id||'').toLowerCase()==='q' ||
                   (el.name||'').toLowerCase()==='s' || (el.id||'').toLowerCase()==='s') return false;
                try{ if(el.closest && el.closest('form[role=search], search')) return false; }catch(e){}
                // explicit username signals
                if(t==='email') return true;
                if(ac.indexOf('username')>=0 || ac.indexOf('email')>=0) return true;
                if(/user|login|email|account|identifier/.test(ni)) return true;
                // generic text-ish field inside a form that ALSO has a password field
                if((t==='text'||t===''||t==='tel') && el.form){
                  try{ if(el.form.querySelector('input[type=password]')) return true; }catch(e){}
                }
                return false;
              }catch(e){ return false; }
            }
            document.addEventListener('focusin', function(e){
              try{
                var el = e.target;
                if(!el) return;
                var isPw = el.type === 'password';
                if(isPw){
                  if(!vis(el)) return;
                  if(window.__zephyrLoginFocus) return;   // existing per-page password guard
                }else{
                  if(!isUsernameField(el) || !vis(el)) return;
                  if(el.getAttribute('data-zephyr-user-offer')) return;   // one offer per field per page
                }
                var now = Date.now();
                if(now - (window.__zephyrLoginFocusAt||0) < 4000) return;   // shared throttle, both triggers
                window.__zephyrLoginFocusAt = now;
                if(isPw) window.__zephyrLoginFocus = true;
                else el.setAttribute('data-zephyr-user-offer','1');
                var host = location.hostname || '';
                if(host && window.Zephyr && window.Zephyr.onLoginFocus) window.Zephyr.onLoginFocus(String(host));
              }catch(err){}
            }, true);
            // SPA: forms appearing later
            var tries = 0;
            var obs = new MutationObserver(function(){
              if(tries++ > 60) { obs.disconnect(); return; }
              if(!pwFields().length) return;
              var pw = pwFields()[0];
              if(pw.value) report(document);
            });
            obs.observe(document.documentElement, {childList:true, subtree:true});
            setTimeout(function(){ obs.disconnect(); }, 30000);
          }catch(e){}
        })();
    """

    /**
     * Payment form detector — v1.7.0 STRICT REWRITE.
     * The v1.4.1 heuristics ('pan' substring, other>=2 via 'exp'/'holder', inputmode
     * numeric with ANY maxlength, load-time scan + MutationObserver auto-notify,
     * paypalLike() hostname check) false-positived on ordinary LOGIN pages (github.com)
     * → the native side toasted "нет сохранённых карт" on pages that had no card form
     * at all. Now: notify ONLY when the user actually FOCUSES a field that passes the
     * STRICT card-number test, the enclosing form has NO password field, and there is
     * ≥1 secondary card signal (cvc/expiry/holder) or the value already looks like a PAN.
     * No load-time checks, no MutationObserver, no hostname heuristics.
     */
    const val PAYMENT_WATCHER = """
        (function(){
          try{
            if(window.__zephyrPayWatch) return; window.__zephyrPayWatch=true;
            function strictCardNumberField(el){
              if(!el || el.tagName!=='INPUT') return false;
              // (a) explicit autocomplete token — the strongest signal
              if((el.getAttribute('autocomplete')||'').indexOf('cc-number')>=0) return true;
              // (b) name/id token match ONLY (placeholder/class are too noisy; 'pan' alone is NOT a card hint)
              var ni = ((el.name||'')+' '+(el.id||'')).toLowerCase();
              if(/card[\s_-]?number|cc[\s_-]?num|ccnumber|cardnum/.test(ni)) return true;
              // (c) numeric inputmode with a card-plausible maxlength
              var ml = parseInt(el.getAttribute('maxlength')||'0',10);
              if((el.getAttribute('inputmode')||'')==='numeric' && ml>=12 && ml<=19) return true;
              // (d) current value already looks like a card number
              var v = el.value||'';
              if(/^[\d\s-]{13,23}$/.test(v) && v.replace(/\D/g,'').length>=13) return true;
              return false;
            }
            function secondarySignal(scope){
              try{
                var els = scope.querySelectorAll('input, select');
                for(var i=0;i<els.length;i++){
                  var el = els[i];
                  var t = ((el.name||'')+' '+(el.id||'')+' '+(el.getAttribute('autocomplete')||'')).toLowerCase();
                  var ph = (el.placeholder||'').toLowerCase();
                  // cvc / cvv / csc (cc-csc contains 'csc')
                  if(t.indexOf('cvc')>=0||t.indexOf('cvv')>=0||t.indexOf('csc')>=0) return true;
                  // expiry: cc-exp* / expmonth / expyear tokens or mm/yy placeholder
                  if(t.indexOf('cc-exp')>=0||t.indexOf('expmonth')>=0||t.indexOf('exp_month')>=0||
                     t.indexOf('expyear')>=0||t.indexOf('exp_year')>=0||/mm\s*\/\s*yy/.test(ph)) return true;
                  // holder: cc-name / cardholder / card-holder / name-on-card
                  if(t.indexOf('cc-name')>=0||t.indexOf('cardholder')>=0||t.indexOf('card-holder')>=0||t.indexOf('name-on-card')>=0) return true;
                }
              }catch(e){}
              return false;
            }
            function formScope(el){
              try{ return el.closest('form') || document; }catch(e){ return document; }
            }
            function hasPasswordField(scope){
              try{ return !!(scope.querySelector && scope.querySelector('input[type=password]')); }catch(e){ return false; }
            }
            function notify(){
              try{ if(window.Zephyr && window.Zephyr.onPaymentForm) window.Zephyr.onPaymentForm(); }catch(e){}
            }
            document.addEventListener('focusin', function(ev){
              try{
                if(window.__zephyrPayFocus) return;   // one offer per page
                var el = ev.target;
                if(!strictCardNumberField(el)) return;
                var scope = formScope(el);
                if(hasPasswordField(scope)) return;   // a login form is never a card form
                var v = el.value||'';
                var looksCard = /^[\d\s-]{13,23}$/.test(v) && v.replace(/\D/g,'').length>=13;
                if(!secondarySignal(scope) && !looksCard) return;
                window.__zephyrPayFocus = true;
                notify();
              }catch(e){}
            }, true);
          }catch(e){}
        })();
    """

    /**
     * v1.7.0 NEW — card SAVE watcher. Complements PAYMENT_WATCHER (which only OFFERS
     * to fill): when the user actually SUBMITS a form (or, for SPA checkouts without
     * a <form>, blurs away from a card field) containing a Luhn-valid 13–19-digit PAN,
     * report it to the native side → "Сохранить карту?" dialog. Never fires on forms
     * with a password field; one report per page per number.
     */
    const val CARD_SAVE = """
        (function(){
          try{
            if(window.__zephyrCardSaveWatch) return; window.__zephyrCardSaveWatch=true;
            function digits(v){ return (v||'').replace(/\D/g,''); }
            function luhn(num){
              try{
                if(num.length<2) return false;
                var sum=0, alt=false;
                for(var i=num.length-1;i>=0;i--){
                  var d=parseInt(num.charAt(i),10);
                  if(isNaN(d)) return false;
                  if(alt){ d*=2; if(d>9) d-=9; }
                  sum+=d; alt=!alt;
                }
                return (sum%10)===0;
              }catch(e){ return false; }
            }
            function strictCardNumberField(el){
              if(!el || el.tagName!=='INPUT') return false;
              if((el.getAttribute('autocomplete')||'').indexOf('cc-number')>=0) return true;
              var ni = ((el.name||'')+' '+(el.id||'')).toLowerCase();
              if(/card[\s_-]?number|cc[\s_-]?num|ccnumber|cardnum/.test(ni)) return true;
              var ml = parseInt(el.getAttribute('maxlength')||'0',10);
              if((el.getAttribute('inputmode')||'')==='numeric' && ml>=12 && ml<=19) return true;
              var v = el.value||'';
              if(/^[\d\s-]{13,23}$/.test(v) && digits(v).length>=13) return true;
              return false;
            }
            function meta(el){
              return ((el.name||'')+' '+(el.id||'')+' '+(el.getAttribute('autocomplete')||'')+' '+
                      (el.placeholder||'')+' '+(el.getAttribute('class')||'')+' '+(el.getAttribute('inputmode')||'')).toLowerCase();
            }
            function formScope(el){
              try{ return el.closest('form') || document; }catch(e){ return document; }
            }
            function hasPasswordField(scope){
              try{ return !!(scope.querySelector && scope.querySelector('input[type=password]')); }catch(e){ return false; }
            }
            function parseExpiry(list){
              var m='', y='';
              for(var i=0;i<list.length;i++){
                var el = list[i], t = meta(el), v = el.value||'';
                if(!m && (t.indexOf('cc-exp-month')>=0||t.indexOf('expmonth')>=0||t.indexOf('exp_month')>=0)){
                  m = digits(v);
                }else if(!y && (t.indexOf('cc-exp-year')>=0||t.indexOf('expyear')>=0||t.indexOf('exp_year')>=0)){
                  y = digits(v);
                }else if(!m && !y && v && (t.indexOf('cc-exp')>=0||t.indexOf('expiry')>=0||t.indexOf('expiration')>=0||
                        /mm\s*\/\s*yy/.test((el.placeholder||'').toLowerCase()))){
                  // combined MM/YY or MM/YYYY field: leading 1-2 digits + trailing 2-4 digits
                  var d = digits(v);
                  if(d.length>=4 && d.length<=6){ m = d.substring(0,2); y = d.substring(2); }
                }
              }
              return [m, y];
            }
            function report(scope){
              try{
                if(!scope || !scope.querySelectorAll || hasPasswordField(scope)) return;
                var list = Array.prototype.slice.call(scope.querySelectorAll('input, select'));
                var numEl = null, num = '';
                for(var i=0;i<list.length;i++){
                  var el = list[i];
                  if(el.tagName!=='INPUT' || !strictCardNumberField(el)) continue;
                  var d = digits(el.value);
                  if(d.length>=13 && d.length<=19 && luhn(d)){ numEl = el; num = d; break; }
                }
                if(!num) return;   // no Luhn-valid PAN → never offer to save
                window.__zephyrCardSave = window.__zephyrCardSave || {};
                if(window.__zephyrCardSave[num]) return;   // one report per page per number
                window.__zephyrCardSave[num] = true;
                var holder = '', cvc = '';
                for(var j=0;j<list.length;j++){
                  var e2 = list[j];
                  if(e2 === numEl) continue;
                  var t = meta(e2);
                  var v2 = String(e2.value||'').trim();
                  if(!holder && (t.indexOf('cc-name')>=0||t.indexOf('cardholder')>=0||t.indexOf('card-holder')>=0||
                                 t.indexOf('holder')>=0||t.indexOf('name-on-card')>=0||t.indexOf('owner')>=0)){
                    holder = v2;
                  }else if(!cvc && (t.indexOf('cc-csc')>=0||t.indexOf('cvc')>=0||t.indexOf('cvv')>=0||
                                    t.indexOf('csc')>=0||t.indexOf('securitycode')>=0||t.indexOf('security-code')>=0)){
                    cvc = v2;
                  }
                }
                var exp = parseExpiry(list);
                try{
                  if(window.Zephyr && window.Zephyr.onCardSubmitted){
                    window.Zephyr.onCardSubmitted(String(num), String(holder), String(exp[0]), String(exp[1]), String(cvc));
                  }
                }catch(e){}
              }catch(e){}
            }
            document.addEventListener('submit', function(ev){
              try{
                var f = ev.target;
                if(f && f.tagName==='FORM') report(f);
              }catch(e){}
            }, true);
            document.addEventListener('focusout', function(ev){
              try{
                var el = ev.target;
                if(!el || el.tagName!=='INPUT') return;
                var d = digits(el.value);
                if(d.length<13 || d.length>19) return;   // card-number-ish AND filled
                if(!strictCardNumberField(el) && !luhn(d)) return;
                report(formScope(el));
              }catch(e){}
            }, true);
          }catch(e){}
        })();
    """

    /** Fill a detected payment form with the chosen saved method (card or PayPal email). */
    fun fillPaymentScript(
        number: String, holder: String, expMonth: String, expYear: String,
        cvv: String, zip: String, isPaypal: Boolean
    ): String {
        val n = JSONObject.quote(number)
        val h = JSONObject.quote(holder)
        val m = JSONObject.quote(expMonth)
        val y = JSONObject.quote(expYear)
        val c = JSONObject.quote(cvv)
        val z = JSONObject.quote(zip)
        return """
        (function(){
          try{
            var N=$n, H=$h, M=$m, Y=$y, C=$c, Z=$z, PP=${if (isPaypal) "true" else "false"};
            function setVal(el, v){
              try{
                if(!el||!v) return;
                el.focus();
                el.value = v;
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                el.dispatchEvent(new KeyboardEvent('keyup', {bubbles:true}));
              }catch(e){}
            }
            function meta(el){
              return ((el.name||'')+' '+(el.id||'')+' '+(el.autocomplete||'')+' '+(el.placeholder||'')+' '+(el.className||'')+' '+(el.getAttribute('inputmode')||'')).toLowerCase();
            }
            var inputs = Array.prototype.slice.call(document.querySelectorAll('input, select'));
            var filled = 0;
            for(var i=0;i<inputs.length;i++){
              var el = inputs[i], t = meta(el);
              var type = el.type || '';
              if(PP){
                if(type==='email'||t.indexOf('email')>=0||t.indexOf('login_email')>=0){ setVal(el, N); filled++; }
                if(t.indexOf('password')>=0 && type==='password') continue;
                continue;
              }
              // card number
              if(t.indexOf('cc-number')>=0||t.indexOf('cardnumber')>=0||t.indexOf('card_number')>=0||t.indexOf('ccnum')>=0||
                 t.indexOf('pan')>=0||(type==='text'&&(el.getAttribute('inputmode')||'')==='numeric')){
                if(!/\d/.test(el.value)) { setVal(el, N); filled++; }
                continue;
              }
              // holder
              if(t.indexOf('cc-name')>=0||t.indexOf('cardholder')>=0||t.indexOf('holder')>=0||t.indexOf('owner')>=0){
                setVal(el, H); filled++; continue;
              }
              // exp month
              if(t.indexOf('cc-exp-month')>=0||t.indexOf('expmonth')>=0||t.indexOf('exp_month')>=0){
                setVal(el, M); filled++; continue;
              }
              // exp year
              if(t.indexOf('cc-exp-year')>=0||t.indexOf('expyear')>=0||t.indexOf('exp_year')>=0){
                setVal(el, Y); filled++; continue;
              }
              // combined exp MM/YY
              if(t.indexOf('cc-exp')>=0||t.indexOf('expiry')>=0||t.indexOf('expiration')>=0||/mm\s*\/\s*yy/.test(el.placeholder||'')){
                setVal(el, M + '/' + Y); filled++; continue;
              }
              // cvc
              if(t.indexOf('cc-csc')>=0||t.indexOf('cvc')>=0||t.indexOf('cvv')>=0||t.indexOf('csc')>=0||t.indexOf('securitycode')>=0||t.indexOf('security-code')>=0){
                setVal(el, C); filled++; continue;
              }
              // zip / postal
              if(t.indexOf('postal')>=0||t.indexOf('zip')>=0){
                setVal(el, Z); filled++; continue;
              }
              // select-based expiry (months list)
              if(el.tagName==='SELECT' && (t.indexOf('month')>=0) && M){
                for(var o=0;o<el.options.length;o++){
                  if(parseInt(el.options[o].value,10)===parseInt(M,10)){ el.selectedIndex=o; el.dispatchEvent(new Event('change',{bubbles:true})); filled++; break; }
                }
                continue;
              }
              if(el.tagName==='SELECT' && (t.indexOf('year')>=0) && Y){
                for(var o2=0;o2<el.options.length;o2++){
                  var v = el.options[o2].value; if(v.length>2) v = v.slice(-2);
                  if(v===Y){ el.selectedIndex=o2; el.dispatchEvent(new Event('change',{bubbles:true})); filled++; break; }
                }
                continue;
              }
            }
            if(!filled){
              // last resort on PayPal-style logins: fill email into the first email field
              if(PP){
                var em = document.querySelector('input[type=email]');
                if(em) setVal(em, N);
              }
            }
          }catch(e){}
        })();
        """.trimIndent()
    }

    /** Fill saved credentials; auto-submit for quick login; SPA retry observer. */
    fun fillScript(originUser: String, originPass: String): String {
        val u = JSONObject.quote(originUser)
        val p = JSONObject.quote(originPass)
        return """
        (function(){
          try{
            var U = $u, P = $p, tries = 0;
            function setVal(el, v){
              try{
                el.focus();
                el.value = v;
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
              }catch(e){}
            }
            function byUserSel(){
              var sels = ['input[type=email]','input[type=tel]','input[autocomplete*=username i]','input[type=text]','input:not([type])'];
              for(var i=0;i<sels.length;i++){
                var list = document.querySelectorAll(sels[i]);
                for(var j=0;j<list.length;j++){
                  var el = list[j];
                  if(!el.value || el.value===U){ if(el.offsetParent!==null || el.getClientRects().length) return el; }
                }
              }
              return null;
            }
            function fill(){
              var uf = byUserSel();
              var pws = document.querySelectorAll('input[type=password]');
              if(!pws.length) return false;
              if(uf) setVal(uf, U);
              setVal(pws[0], P);
              return true;
            }
            function submitForm(){
              try{
                var pw = document.querySelector('input[type=password]');
                var f = pw ? (pw.form || pw.closest('form')) : null;
                if(f){
                  if(f.requestSubmit) f.requestSubmit();
                  else if(typeof f.submit === 'function') f.submit();
                  else{
                    var btn = f.querySelector('button[type=submit],input[type=submit],button:not([type])');
                    if(btn) btn.click();
                  }
                }
              }catch(e){}
            }
            if(!fill()) return;
            window.__zephyrAutoSubmitTimer = setTimeout(submitForm, 400);
            var obs = new MutationObserver(function(){
              if(tries++ > 20) { obs.disconnect(); return; }
              if(!document.querySelector('input[type=password]') || (document.activeElement && document.activeElement.type==='password' && document.activeElement.value)) { obs.disconnect(); return; }
              if(fill()){ obs.disconnect(); clearTimeout(window.__zephyrAutoSubmitTimer); setTimeout(submitForm, 400); }
            });
            obs.observe(document.documentElement, {childList:true, subtree:true});
            setTimeout(function(){ obs.disconnect(); }, 15000);
          }catch(e){}
        })();
        """.trimIndent()
    }

    /**
     * v1.7.4 REWRITE — YouTube ad blocker v4: the START DELAY is gone.
     *
     * Root cause of the 3–10 s spinner (v1.7.3 symptom): ads were removed from the
     * page only AFTER the player had already received their metadata — the JSON.parse
     * surgery never sees bodies parsed by the BROWSER (fetch().json() and XHR
     * responseType:'json' skip the page-level JSON.parse entirely). So the player
     * scheduled the preroll, requested ad creative/metadata, got an empty fake VAST
     * or a network-level block, and then waited out its own ad-slot timeout before
     * starting the content — the "loading circle on a black screen".
     *
     * v4 uses the technique uBlock Origin ships for Safari/Firefox-iOS (xml-prune /
     * playlist-json modification): the ad fields are deleted from the player
     * response BEFORE the player ever reads it, so no ad is ever scheduled:
     *  - fetch hook: every /youtubei/v1/... response is cloned, parsed and stripped of
     *    adPlacements / adSlots / playerAds / adSlotsHeader /
     *    adBreakHeartbeatParams; a pruned copy is returned as a fresh Response
     *    (the original stays unread when nothing had to change — zero overhead);
     *  - XHR hook: the responseText / response GETTERS on the prototype are
     *    re-defined so BOTH text and responseType:'json' XHRs hand the player a
     *    pruned payload (lazy + cached per request — the uBO xml-prune trick);
     *  - ytInitialPlayerResponse (the player response inlined into the watch-page
     *    HTML) is trapped via a defineProperty setter armed at document-start —
     *    the page script assigns the object, we strip the ad keys first;
     *  - the v1.7.2/v3 defenses stay as fallback layers: fake empty-VAST fetch
     *    answers for ad URLs, JSON.parse surgery for page-side parses, full CSS
     *    hiding (+ :has() and the DOM-walk fallback), the 500 ms skip loop +
     *    MutationObserver, non-skippable boost (mute + 16x + seek to end) and
     *    the SPA re-arm handlers (yt-navigate-finish / popstate / pushState).
     * googlevideo.com (the real video CDN) is matched only by its three ad/tracing
     * path keywords — /videoplayback media always passes through untouched.
     * Idempotent via window.__zephyrYtBlock4.
     */
    const val YT_ADBLOCK = """
        (function(){
          if(window.__zephyrYtBlock4) return; window.__zephyrYtBlock4=1;
          try{
            // ===== A. ad-URL detector: exact ad hosts/paths only =====
            var AD_PAT=['pagead2.googlesyndication.com','googleads.g.doubleclick.net',
              'www.googleadservices.com','adservice.google.com','ads.youtube.com',
              'doubleclick.net/pagead','doubleclick.net/pcs','youtube.com/pagead',
              'youtube.com/api/stats/ads','youtube.com/ptracking','youtube.com/ptrace/',
              'youtube.com/get_midroll_info_','/adx/','pagead/lve'];
            function isAdUrl(u){
              try{
                if(!u||typeof u!=='string') return false;
                var s=u.toLowerCase();
                if(s.indexOf('/vast')===0||/^[a-z][a-z0-9+.\-]*:\/\/[^\/]+\/vast/.test(s)) return true;
                if(s.charAt(0)==='/'&&s.charAt(1)!=='/'){
                  try{ var h=location.hostname.toLowerCase(); if(h) s=h+s; }catch(e2){}
                }
                if(s.indexOf('googlevideo.com')>=0){
                  return s.indexOf('/pagead')>=0||s.indexOf('/ptrace/')>=0||s.indexOf('/api/stats/ads')>=0;
                }
                for(var i=0;i<AD_PAT.length;i++){ if(s.indexOf(AD_PAT[i])>=0) return true; }
                if(s.indexOf('googlesyndication.com')>=0&&s.indexOf('adunit')>=0) return true;
                return false;
              }catch(e){ return false; }
            }
            // ===== B. the pruner: deletes ad scheduling from player responses =====
            var AD_KEYS=['adPlacements','adSlots','playerAds','adSlotsHeader','adBreakHeartbeatParams'];
            function strip(o,depth){
              if(!o||depth>10) return;
              if(Array.isArray(o)){ for(var i=0;i<o.length;i++) strip(o[i],depth+1); return; }
              if(typeof o!=='object') return;
              for(var j=0;j<AD_KEYS.length;j++){ try{ delete o[AD_KEYS[j]]; }catch(e2){} }
              for(var k in o){ try{ strip(o[k],depth+1); }catch(e3){} }
            }
            function hasAdText(t){
              return typeof t==='string'&&(t.indexOf('adPlacements')>=0||
                t.indexOf('adSlots')>=0||t.indexOf('playerAds')>=0);
            }
            function pruneText(t){
              try{
                var o=JSON.parse(t);
                strip(o,0);
                return JSON.stringify(o);
              }catch(e){ return t; }
            }
            function pruneObj(o){
              try{ if(o&&(typeof o==='object')) strip(o,0); }catch(e){}
            }
            function isYtApi(u){
              try{ return !!u&&typeof u==='string'&&u.indexOf('/youtubei/v1/')>=0; }catch(e){ return false; }
            }
            // ===== C. ytInitialPlayerResponse trap (inlined in the watch-page HTML) =====
            try{
              if(window.ytInitialPlayerResponse) pruneObj(window.ytInitialPlayerResponse);
              var __ytipr=window.ytInitialPlayerResponse;
              Object.defineProperty(window,'ytInitialPlayerResponse',{
                configurable:true,
                get:function(){ return __ytipr; },
                set:function(v){ pruneObj(v); __ytipr=v; }
              });
            }catch(e){}
            // ===== D. fetch hook: fake ad answers + PRUNE player API responses =====
            var origFetch=window.fetch&&window.fetch.bind(window);
            if(origFetch){
              window.fetch=function(input,init){
                var u='';
                try{ u=(typeof input==='string')?input:((input&&input.url)||''); }catch(e0){}
                try{
                  if(u&&isAdUrl(u)){
                    var vast=/\/vast|pagead|adx|midroll/.test(u.toLowerCase());
                    var body=vast?'<VAST version="4.0"></VAST>':'';
                    return Promise.resolve(new Response(body,{status:200,
                      headers:{'Content-Type':vast?'application/xml':'text/plain'}}));
                  }
                }catch(e){}
                var p;
                try{ p=origFetch.apply(this,arguments); }
                catch(e1){ return Promise.reject(e1); }
                try{
                  if(isYtApi(u)){
                    return p.then(function(res){
                      try{
                        if(!res||res.status!==200||!res.clone) return res;
                        var ct='';
                        try{ ct=(res.headers&&res.headers.get&&res.headers.get('content-type'))||''; }catch(e2){}
                        if(ct&&ct.indexOf('json')<0) return res;
                        return res.clone().text().then(function(t){
                          if(!hasAdText(t)) return res;
                          var t2=pruneText(t);
                          if(t2===t) return res;
                          return new Response(t2,{status:res.status,statusText:res.statusText,
                            headers:{'Content-Type':ct||'application/json'}});
                        });
                      }catch(e3){ return res; }
                    },function(err){ throw err; });
                  }
                }catch(e4){}
                return p;
              };
            }
            // ===== E. XHR hook: fake ad completion + PRUNE player responses =====
            // The prototype getters are re-defined so BOTH responseText and
            // responseType:'json' requests hand the player a pruned payload
            // (lazy, cached per request — the uBO xml-prune technique).
            var XO=XMLHttpRequest.prototype.open,XS=XMLHttpRequest.prototype.send;
            var xTextDesc=null,xRespDesc=null;
            try{ xTextDesc=Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype,'responseText'); }catch(e5){}
            try{ xRespDesc=Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype,'response'); }catch(e6){}
            XMLHttpRequest.prototype.open=function(m,url){
              this.__zu=url||''; this.__zad=false; this.__zytApi=isYtApi(url);
              try{ this.__zad=isAdUrl(url); }catch(e7){}
              return XO.apply(this,arguments);
            };
            if(xTextDesc&&xTextDesc.get){
              try{
                Object.defineProperty(XMLHttpRequest.prototype,'responseText',{
                  configurable:true,
                  get:function(){
                    if(this.__zprunedText!==undefined) return this.__zprunedText;
                    var v=xTextDesc.get.call(this);
                    if(this.__zytApi&&hasAdText(v)){
                      v=pruneText(v);
                      try{ Object.defineProperty(this,'__zprunedText',{value:v,configurable:true}); }catch(e8){}
                    }
                    return v;
                  }
                });
              }catch(e9){}
            }
            if(xRespDesc&&xRespDesc.get){
              try{
                Object.defineProperty(XMLHttpRequest.prototype,'response',{
                  configurable:true,
                  get:function(){
                    if(this.__zprunedVal!==undefined) return this.__zprunedVal;
                    var v=xRespDesc.get.call(this);
                    if(this.__zytApi&&v!==null&&v!==undefined){
                      if(typeof v==='string'&&hasAdText(v)){
                        var v2=pruneText(v);
                        if(v2!==v){
                          v=v2;
                          try{ Object.defineProperty(this,'__zprunedVal',{value:v,configurable:true}); }catch(e10){}
                        }
                      }else if(typeof v==='object'){
                        pruneObj(v);
                      }
                    }
                    return v;
                  }
                });
              }catch(e11){}
            }
            XMLHttpRequest.prototype.send=function(){
              if(this.__zad){
                try{
                  var x=this,u=this.__zu||'';
                  try{Object.defineProperty(x,'responseText',{value:'',configurable:true});}catch(e12){}
                  try{Object.defineProperty(x,'response',{value:'',configurable:true});}catch(e13){}
                  try{Object.defineProperty(x,'status',{value:200,configurable:true});}catch(e14){}
                  try{Object.defineProperty(x,'statusText',{value:'OK',configurable:true});}catch(e15){}
                  try{Object.defineProperty(x,'readyState',{value:4,configurable:true});}catch(e16){}
                  try{Object.defineProperty(x,'responseURL',{value:u,configurable:true});}catch(e17){}
                  setTimeout(function(){
                    try{
                      if(typeof x.onreadystatechange==='function') x.onreadystatechange();
                      if(typeof x.onload==='function') x.onload();
                      try{ x.dispatchEvent(new Event('readystatechange')); }catch(e18){}
                      try{ x.dispatchEvent(new Event('load')); }catch(e19){}
                      try{ x.dispatchEvent(new Event('loadend')); }catch(e20){}
                    }catch(e21){}
                  },0);
                  return;
                }catch(e22){}
              }
              return XS.apply(this,arguments);
            };
            // ===== F. JSON.parse surgery (page-side parses of ad payloads) =====
            var origParse=JSON.parse;
            JSON.parse=function(text){
              var r=origParse.apply(this,arguments);
              try{
                if(typeof text==='string'&&hasAdText(text)) strip(r,0);
              }catch(e23){}
              return r;
            };
            // ===== G. CSS hiding (v2 port): plain block + :has() block + DOM fallback =====
            var cssSafe='#player-ads,#masthead-ad,ytd-ad-slot-renderer,ytd-display-ad-renderer,'+
              'ytd-promoted-video-renderer,ytd-in-feed-ad-layout-renderer,.video-ads,'+
              '.ytp-ad-module,.ytp-ad-image-overlay,.ytp-ad-text-overlay,.ytp-ad-overlay-container,'+
              '.ytp-ad-preview-container,ytd-engagement-panel-section-list-renderer[target-id*=ad],'+
              'ytd-banner-promo-renderer,ytd-statement-banner-renderer,.badge-style-type-ad,#ad-badge,'+
              '.ytd-ad-badge-and-renderer,#ad-text,.badge-shape-wiz-badge-shape--ad,'+
              'ytd-ad-slot-renderer ytd-badge-supported-renderer,'+
              'ytd-display-ad-renderer ytd-badge-supported-renderer,'+
              '#dialog ytd-enforcement-message-renderer{display:none!important;}';
            var cssHas='ytd-rich-item-renderer:has(ytd-ad-slot-renderer),'+
              'ytd-rich-item-renderer:has(.ytd-display-ad-renderer),'+
              'ytd-rich-item-renderer:has(ytd-display-ad-renderer),'+
              'ytd-rich-item-renderer:has(ytd-promoted-video-renderer),'+
              '#contents>ytd-shelf-renderer:has(ytd-ad-slot-renderer),'+
              '#contents>ytd-shelf-renderer:has(.ytd-display-ad-renderer),'+
              '#contents>ytd-shelf-renderer:has(ytd-display-ad-renderer),'+
              'ytd-reel-video-renderer:has(.ytd-reel-ad-segment-renderer),'+
              'ytd-reel-video-renderer:has(.badge-style-type-ad),'+
              'ytd-reel-video-renderer:has(ytd-ad-slot-renderer),'+
              'ytd-reel-video-renderer:has(#ad-text){display:none!important;}';
            function addCss(){
              try{
                var s=document.getElementById('__zephyrYtCss');
                if(!s){ s=document.createElement('style'); s.id='__zephyrYtCss';
                  (document.head||document.documentElement).appendChild(s); }
                s.textContent=cssSafe+cssHas;
              }catch(e24){}
            }
            // JS fallback for WebViews without :has() — same ad containers, found by scanning
            function hideAdCards(){
              try{
                var hosts=document.querySelectorAll('ytd-rich-item-renderer,ytd-reel-video-renderer,#contents>ytd-shelf-renderer');
                for(var i=0;i<hosts.length;i++){
                  var el=hosts[i];
                  var ad=el.querySelector('ytd-ad-slot-renderer,.ytd-display-ad-renderer,ytd-promoted-video-renderer,ytd-in-feed-ad-layout-renderer,.badge-style-type-ad,#ad-badge,#ad-text,.ytd-ad-badge-and-renderer,.badge-shape-wiz-badge-shape--ad,.ytd-reel-ad-segment-renderer');
                  if(ad){ try{ el.style.setProperty('display','none','important'); }catch(e25){} }
                }
              }catch(e26){}
            }
            // ===== H. auto-skip + non-skippable boost (v2 port) =====
            function clickSkips(){
              var clicked=false;
              try{
                var sels=['.ytp-ad-skip-button-modern','.ytp-ad-skip-button','.ytp-skip-button',
                          'button.ytp-skip-button','.ytp-ad-skip-button-container button',
                          '.ytp-ad-skip-button-modern-container button',
                          'tp-yt-paper-button[class*="skip-button"]'];
                for(var i=0;i<sels.length;i++){
                  var btns=document.querySelectorAll(sels[i]);
                  for(var j=0;j<btns.length;j++){ try{ btns[j].click(); clicked=true; }catch(e27){} }
                }
                var all=document.querySelectorAll('[class*="skip-button"]');
                for(var k=0;k<all.length;k++){
                  var el=all[k];
                  try{ if(el.offsetParent!==null||el.getClientRects().length){ el.click(); clicked=true; } }catch(e28){}
                }
              }catch(e29){}
              return clicked;
            }
            var boosted=false, wasMuted=false, wasRate=1;
            function adEndFromDom(){
              try{
                var d=document.querySelector('.ytp-ad-duration,.ad-duration');
                if(d){
                  var p=String(d.textContent||'').trim().split(':'),sec=0;
                  for(var i=0;i<p.length;i++){ var n=parseFloat(p[i]); if(isFinite(n)) sec=sec*60+n; }
                  if(sec>0) return sec;
                }
              }catch(e30){}
              return 0;
            }
            function boost(v){
              try{
                if(!boosted){
                  boosted=true;
                  wasMuted=v.muted;
                  wasRate=(v.playbackRate&&isFinite(v.playbackRate)&&v.playbackRate>0)?v.playbackRate:1;
                }
                v.muted=true;
                v.playbackRate=16;
                var adMod=document.querySelector('.ytp-ad-module');
                if(adMod&&adMod.children&&adMod.children.length&&v.duration>0&&isFinite(v.duration)&&v.currentTime<v.duration-1){
                  try{ v.currentTime=v.duration-0.05; }catch(e31){}
                }else{
                  var ae=adEndFromDom();
                  if(ae>0&&v.currentTime<ae-1){ try{ v.currentTime=ae-0.05; }catch(e32){} }
                }
              }catch(e33){}
            }
            function restore(v){
              try{
                if(boosted){ boosted=false; v.muted=wasMuted; v.playbackRate=wasRate; }
              }catch(e34){}
            }
            function skipAd(){
              try{
                var v=document.querySelector('video.html5-main-video')||document.querySelector('video');
                if(!v) return;
                var player=document.getElementById('movie_player');
                var adShowing=false;
                try{ adShowing=!!(player&&player.classList&&player.classList.contains('ytp-ad-showing')); }catch(e35){}
                var adMod=document.querySelector('.ytp-ad-module');
                var adOn=adShowing||(adMod&&adMod.children&&adMod.children.length>0);
                if(!adOn){ if(boosted) restore(v); return; }
                if(!clickSkips()) boost(v);
              }catch(e36){}
            }
            var mutCount=0, armed=false;
            var obs=new MutationObserver(function(){
              try{
                mutCount++;
                if((mutCount&7)===0){ addCss(); hideAdCards(); }
                skipAd();
                if(mutCount>=600){ obs.disconnect(); armed=false; setTimeout(function(){ arm(); },2000); }
              }catch(e37){}
            });
            function arm(){
              try{
                var root=document.documentElement;
                if(!root){ setTimeout(arm,50); return; }
                mutCount=0; armed=true;
                obs.observe(root,{childList:true,subtree:true});
              }catch(e38){}
            }
            arm();
            setInterval(skipAd,500);
            // ===== I. SPA handlers (v2 port) =====
            function onNav(){ try{ if(!armed) arm(); }catch(e39){} addCss(); hideAdCards(); skipAd(); }
            document.addEventListener('yt-navigate-finish',onNav,true);
            window.addEventListener('popstate',onNav,true);
            try{
              var hp=history.pushState, hr=history.replaceState;
              if(typeof hp==='function'){
                history.pushState=function(){ var r=hp.apply(this,arguments); try{setTimeout(onNav,300);}catch(e40){} return r; };
              }
              if(typeof hr==='function'){
                history.replaceState=function(){ var r=hr.apply(this,arguments); try{setTimeout(onNav,300);}catch(e41){} return r; };
              }
            }catch(e42){}
            document.addEventListener('visibilitychange',function(){ if(!document.hidden) onNav(); },true);
            addCss(); hideAdCards();
          }catch(e43){}
        })();
    """

    /**
     * v1.7.2: SponsorBlock — skips sponsored segments INSIDE the video (in-stream
     * "this video is sponsored by…" parts are not ads any network filter can block;
     * the segment data comes from the community database at sponsor.ajay.app,
     * whose API sends permissive CORS headers so a plain page fetch works).
     *  - fetches skipSegments for the current video (categories: sponsor,
     *    selfpromo, interaction, intro), cached per video in sessionStorage;
     *  - 200ms monitor: when video.currentTime enters a segment it seeks to the
     *    segment end and shows a toast badge bottom-left of the player
     *    ("Спонсор пропущен · SponsorBlock" with the category label), idempotent
     *    per segment UUID, auto-fades after 2.5s;
     *  - videoId from the v= query param, the shorts path, or
     *    ytInitialPlayerResponse; SPA navigations caught via yt-navigate-finish +
     *    popstate + a throttled MutationObserver;
     *  - never runs while an ad is playing (segment times refer to the content
     *    timeline); silently no-ops when the API fails or nothing matches;
     *  - idempotent: window.__zephyrSponsorBlock guard. Injected from
     *    MainActivity.onPageFinished (v1.7.3: YT_ADBLOCK itself moved to
     *    onPageStarted/document-start), behind the same Prefs.youtubeAdBlock
     *    gate; the script itself only acts on watch pages.
     */
    /**
     * v1.7.2: SponsorBlock with the user-selected CATEGORY set from Prefs
     * (Settings → AdBlock → SponsorBlock: Спонсор/Самореклама/Обращение/Заставка).
     */
    fun sponsorBlockJs(sponsor: Boolean, selfpromo: Boolean, interaction: Boolean, intro: Boolean): String {
        val cats = buildList {
            if (sponsor) add("'sponsor'")
            if (selfpromo) add("'selfpromo'")
            if (interaction) add("'interaction'")
            if (intro) add("'intro'")
        }
        if (cats.isEmpty()) return ""   // everything disabled → don't inject at all
        return SPONSORBLOCK.replace("__SB_CATS__", "[${cats.joinToString(",")}]")
    }

    const val SPONSORBLOCK = """        (function(){
          try{
            if(window.__zephyrSponsorBlock) return; window.__zephyrSponsorBlock=true;
            var CAT_LABELS={sponsor:'Спонсор',selfpromo:'Самореклама',interaction:'Обращение',intro:'Заставка'};
            var CATS=__SB_CATS__;
            var segments=[], currentVid=null, skipped={}, lastRecheck=0;
            function isWatchPage(){
              try{ return location.pathname.indexOf('/watch')===0||location.pathname.indexOf('/shorts/')===0; }catch(e){ return false; }
            }
            function videoId(){
              try{
                var m=location.search.match(/[?&]v=([a-zA-Z0-9_-]{6,})/);
                if(m) return m[1];
                var sm=location.pathname.match(/\/shorts\/([a-zA-Z0-9_-]{6,})/);
                if(sm) return sm[1];
                var pr=window.ytInitialPlayerResponse;
                if(pr&&pr.videoDetails&&pr.videoDetails.videoId) return pr.videoDetails.videoId;
              }catch(e){}
              return null;
            }
            function showToast(category){
              try{
                var host=document.querySelector('.html5-video-player')||document.getElementById('movie_player')||document.body;
                if(!host) return;
                var old=document.getElementById('__zephyrSbToast');
                if(old){ try{ old.remove(); }catch(e){} }
                var d=document.createElement('div');
                d.id='__zephyrSbToast';
                var label=CAT_LABELS[category]||'Спонсор';
                d.textContent='⏩ '+label+' пропущен · SponsorBlock';
                d.setAttribute('style','position:absolute;left:12px;bottom:64px;z-index:2147483646;pointer-events:none;'+
                  'background:rgba(20,20,20,0.78);color:#fff;font:500 13px/1.4 Roboto,Arial,sans-serif;'+
                  'padding:8px 14px;border-radius:10px;max-width:70%;box-shadow:0 2px 10px rgba(0,0,0,0.45);opacity:1;transition:opacity .4s;');
                host.appendChild(d);
                setTimeout(function(){ try{ d.style.opacity='0'; setTimeout(function(){ try{ d.remove(); }catch(e){} },450); }catch(e){} },2500);
              }catch(e){}
            }
            function applySegments(arr){
              try{
                segments=[];
                for(var i=0;i<arr.length;i++){
                  var o=arr[i]||{}, seg=o.segment;
                  if(seg&&typeof seg[0]==='number'&&typeof seg[1]==='number'&&seg[1]>seg[0]+0.3){
                    segments.push({start:seg[0],end:seg[1],category:o.category||'sponsor',
                      uuid:String(o.UUID||((o.category||'sponsor')+':'+seg[0]))});
                  }
                }
              }catch(e){}
            }
            function loadSegments(vid){
              currentVid=vid; segments=[]; skipped={};
              if(!vid) return;
              try{
                var key='zsb:'+vid, cached=null;
                try{ cached=sessionStorage.getItem(key); }catch(e){}
                if(cached!==null&&cached!==undefined){
                  try{ applySegments(JSON.parse(cached)); return; }catch(e){}
                }
                var url='https://sponsor.ajay.app/api/skipSegments?videoID='+encodeURIComponent(vid)+
                  '&categories='+encodeURIComponent(JSON.stringify(CATS));
                fetch(url).then(function(r){ return r.ok?r.json():[]; }).then(function(arr){
                  arr=arr||[];
                  try{ sessionStorage.setItem(key,JSON.stringify(arr)); }catch(e){}
                  if(currentVid===vid) applySegments(arr);
                }).catch(function(){});
              }catch(e){}
            }
            function adPlaying(){
              try{
                var p=document.getElementById('movie_player');
                if(p&&p.classList&&p.classList.contains('ytp-ad-showing')) return true;
                var adMod=document.querySelector('.ytp-ad-module');
                if(adMod&&adMod.children&&adMod.children.length) return true;
              }catch(e){}
              return false;
            }
            function checkSkip(){
              try{
                if(!segments.length||!isWatchPage()) return;
                if(adPlaying()) return;
                var v=document.querySelector('video.html5-main-video')||document.querySelector('video');
                if(!v||!v.duration||!isFinite(v.duration)) return;
                var t=v.currentTime;
                for(var i=0;i<segments.length;i++){
                  var s=segments[i];
                  if(t>=s.start-0.25&&t<s.end-0.05){
                    try{ v.currentTime=s.end+0.05; }catch(e){}
                    if(!skipped[s.uuid]){ skipped[s.uuid]=true; showToast(s.category); }
                  }
                }
              }catch(e){}
            }
            function recheck(){
              try{
                var now=Date.now();
                if(now-lastRecheck<400) return;
                lastRecheck=now;
                if(!isWatchPage()) return;
                var vid=videoId();
                if(vid&&vid!==currentVid) loadSegments(vid);
              }catch(e){}
            }
            setInterval(checkSkip,200);
            document.addEventListener('yt-navigate-finish',recheck,true);
            window.addEventListener('popstate',recheck,true);
            try{
              var mo=new MutationObserver(recheck);
              mo.observe(document.documentElement,{childList:true,subtree:true});
            }catch(e){}
            recheck();
            setTimeout(recheck,800);
            setTimeout(recheck,2500);
          }catch(e){}
        })();
    """

    /**
     * v1.5.1: Identity-form detector — a visible form with >= 2 identity-ish fields
     * (name / email / phone / address / city / zip / organization) and NO password
     * field. Notifies native once per page → profile autofill offer.
     */
    const val IDENTITY_WATCHER = """
        (function(){
          try{
            if(window.__zephyrIdWatch) return; window.__zephyrIdWatch=true;
            function vis(el){ try{ return el.offsetParent!==null || el.getClientRects().length; }catch(e){ return false; } }
            function meta(el){
              return ((el.name||'')+' '+(el.id||'')+' '+(el.autocomplete||'')+' '+(el.placeholder||'')+' '+(el.className||'')).toLowerCase();
            }
            function isIdentityField(el){
              var t=meta(el);
              var hints=['email','tel','phone','given-name','family-name','name','street','address','city','zip','postal','organization','company','fname','lname','fullname','mobile'];
              for(var i=0;i<hints.length;i++){ if(t.indexOf(hints[i])>=0) return true; }
              return false;
            }
            function check(){
              try{
                if(document.querySelector('input[type=password]')) return false;
                var inputs=Array.prototype.slice.call(document.querySelectorAll('input')).filter(vis);
                var hits=0;
                for(var i=0;i<inputs.length;i++){ if(isIdentityField(inputs[i])) hits++; }
                return hits>=2;
              }catch(e){ return false; }
            }
            function notify(){
              try{ if(window.Zephyr && window.Zephyr.onIdentityForm) window.Zephyr.onIdentityForm(); }catch(e){}
            }
            function arm(){
              if(check()){ notify(); return; }
              var tries=0;
              var obs=new MutationObserver(function(){
                if(tries++>30){ obs.disconnect(); return; }
                if(check()){ obs.disconnect(); notify(); }
              });
              obs.observe(document.documentElement,{childList:true,subtree:true});
              setTimeout(function(){ try{ obs.disconnect(); }catch(e){} }, 15000);
            }
            if(document.readyState==='complete') setTimeout(arm, 500);
            else document.addEventListener('DOMContentLoaded', function(){ setTimeout(arm, 500); });
          }catch(e){}
        })();
    """

    /**
     * v1.5.1: Fill an identity form with a saved autofill profile.
     * [fields] is a flat JSON object (name, email, phone, address, city, zip, ...).
     * Keys are matched against autocomplete hints first, then name/id/placeholder tokens.
     */
    fun profileFillScript(fields: JSONObject): String {
        val json = fields.toString()
        return """
        (function(){
          try{
            var F=$json;
            function meta(el){
              return ((el.name||'')+' '+(el.id||'')+' '+(el.autocomplete||'')+' '+(el.placeholder||'')+' '+(el.className||'')).toLowerCase();
            }
            function setVal(el, v){
              try{
                if(!el||!v) return;
                el.focus();
                el.value=v;
                el.dispatchEvent(new Event('input',{bubbles:true}));
                el.dispatchEvent(new Event('change',{bubbles:true}));
              }catch(e){}
            }
            var inputs=Array.prototype.slice.call(document.querySelectorAll('input'));
            var filled=0;
            for(var i=0;i<inputs.length;i++){
              var el=inputs[i], t=meta(el);
              var type=el.type||'';
              if(type==='password'||type==='checkbox'||type==='radio'||type==='file'||type==='hidden'||type==='button'||type==='submit') continue;
              if(el.offsetParent===null && !el.getClientRects().length) continue;
              var done=false;
              var keys=Object.keys(F);
              for(var k=0;k<keys.length && !done;k++){
                var key=keys[k], val=F[key];
                if(!val) continue;
                var kk=key.toLowerCase();
                var hit=false;
                if(t.indexOf(kk)>=0) hit=true;
                if(!hit && (el.autocomplete||'').toLowerCase().indexOf(kk)>=0) hit=true;
                if(!hit){
                  var syn={ 'tel':'phone mobile', 'name':'fname fullname given-name family-name lname',
                            'street':'address address1', 'zip':'postal',
                            'organization':'company org' };
                  if(syn[kk] && t.indexOf(syn[kk])>=0) hit=true;
                }
                if(hit){
                  if(!/\S/.test(el.value||'')){ setVal(el, val); filled++; }
                  done=true;
                }
              }
            }
            return filled;
          }catch(e){ return 0; }
        })();
        """.trimIndent()
    }

    /** WebRTC disable (privacy). */
    const val BLOCK_WEBRTC = """
        (function(){
          try{
            Object.defineProperty(window, 'RTCPeerConnection', {get:function(){return undefined;}, configurable:false});
            Object.defineProperty(window, 'webkitRTCPeerConnection', {get:function(){return undefined;}, configurable:false});
            if(navigator.mediaDevices && navigator.mediaDevices.getUserMedia){
              navigator.mediaDevices.getUserMedia = function(){ return Promise.reject(new DOMException('Blocked', 'NotAllowedError')); };
            }
          }catch(e){}
        })();
    """

    /**
     * Do-Not-Track: WebView exposes no header-injection API, so we signal via the
     * standard navigator property (what JS-based sites read) on every page load.
     */
    const val DO_NOT_TRACK = """
        (function(){
          try{
            Object.defineProperty(navigator, 'doNotTrack', {get:function(){return '1';}, configurable:true});
          }catch(e){}
        })();
    """

    /** Page appearance CSS (sepia / grayscale / contrast / css-dark fallback).
     *  v1.5.0 FIX (dark never applied, sepia worked): the unquoted attribute selector
     *  is REQUIRED — the old [style*='background-image'] contained single quotes that
     *  TERMINATED the JS string literal ('html{...}') → the whole script was a syntax
     *  error inside try/catch → silently dead. Unquoted values are valid CSS identifiers. */
    fun appearanceCss(appearance: Int): String {
        val css = when (appearance) {
            3 -> "filter:sepia(0.62) brightness(0.96) saturate(0.9);"
            4 -> "filter:grayscale(1);"
            5 -> "filter:contrast(1.14) brightness(1.06) saturate(1.05);"
            2 -> "filter:invert(1) hue-rotate(180deg);background-color:#fff!important;"
            else -> return ""
        }
        val extra = if (appearance == 2) {
            "html img,html video,html picture,html canvas,html [style*=background-image]{filter:invert(1) hue-rotate(180deg);}"
        } else ""
        val js = """
        (function(){
          try{
            var id='__zephyrLook';
            var st=document.getElementById(id);
            if(!st){ st=document.createElement('style'); st.id=id; (document.head||document.documentElement).appendChild(st); }
            st.textContent='html{$css}$extra';
          }catch(e){}
        })();
        """.trimIndent()
        return js
    }

    /** Reader-mode extraction. Returns JSON {title, byline, siteName, html, text}. */
    const val READER_EXTRACT = """
        (function(){
          function q(sel){ var e=document.querySelector(sel); return e?e:null; }
          function meta(name){ var e=q('meta[property="'+name+'"]')||q('meta[name="'+name+'"]'); return e&&e.content?e.content:''; }
          function pick(sel, root){
            root = root || document;
            var els = root.querySelectorAll(sel);
            var best=null, bestScore=0;
            for(var i=0;i<els.length;i++){
              var el=els[i];
              var len=(el.textContent||'').trim().length;
              if(len>bestScore){bestScore=len;best=el;}
            }
            return best;
          }
          var cand = pick('article') || pick('[role=main]') || pick('main') || pick('section article') ||
                     pick('.article-content,.post-content,.entry-content,.article__body,.story-body') ||
                     pick('article,body,div');
          if(!cand) return null;
          var clone = cand.cloneNode(true);
          var junk = clone.querySelectorAll('script,style,nav,aside,footer,header,form,iframe,noscript,button,.ad,.ads,.advert,.social,.share,.comments,.related,.recommend,[role=navigation],[role=complementary],[aria-hidden=true]');
          for(var i=0;i<junk.length;i++) junk[i].remove();
          var title = meta('og:title') || document.title || '';
          var authEl = q('[class*=author]') || q('[rel=author]');
          var byline = meta('author') || (authEl ? authEl.textContent : '') || '';
          var site = meta('og:site_name') || location.hostname;
          var text = (clone.textContent||'').replace(/\s+/g,' ').trim();
          var html = '<div>' + (clone.innerHTML||'') + '</div>';
          return JSON.stringify({title:String(title).trim(), byline:String(byline||'').trim().slice(0,80), siteName:site, len:text.length, html:html.slice(0,300000)});
        })();
    """

    /** Collect favicon hrefs from DOM → notify bridge with first big-enough one. */
    const val FAVICON_SNIFF = """
        (function(){
          try{
            var links = document.querySelectorAll('link[rel*=icon i]');
            var best=null;
            for(var i=0;i<links.length;i++){
              var href=links[i].href;
              if(href && /^https?:/.test(href) && (!best || (links[i].rel||'').indexOf('apple')<0)) best=href;
            }
            if(!best) best = location.origin + '/favicon.ico';
            if(window.Zephyr && window.Zephyr.notifyFavicon) window.Zephyr.notifyFavicon(best);
          }catch(e){}
        })();
    """

    /** Desktop-mode viewport width fix. */
    const val DESKTOP_VIEWPORT = """
        (function(){
          try{
            var m=document.querySelector('meta[name=viewport]');
            if(m) m.setAttribute('content','width=1280');
            else{
              m=document.createElement('meta');m.name='viewport';m.setAttribute('content','width=1280');
              (document.head||document.documentElement).appendChild(m);
            }
          }catch(e){}
        })();
    """

    /**
     * v1.7.15 (user: «на сайте чата потянуть вверх уже срабатывает обновление
     * страницы вместо прокрутки — всё зависит от сайта»): JS scroll-state gate
     * for pull-to-refresh. SPA pages (z.ai chat etc.) scroll INSIDE inner
     * containers — the WebView itself always reports "at top", so SmartPull's
     * canScrollVertically(-1) check armed the refresh on pages whose chat
     * history was actually scrolled mid-way. This script watches touchstart/
     * touchend (capture, passive) and reports whether the PAGE itself or any
     * ANCESTOR of the touched element can scroll up (scrollTop > 0) via
     * window.Zephyr.reportScroll(boolean); native disarms the gesture while
     * that is true. Idempotent (window.__zephyrScrollGate guard), everything
     * in try/catch, reports once up-front for the already-scrolled case.
     * v1.7.16: a live 'scroll' listener (capture, passive, throttled to
     * 100ms) also updates the state DURING gestures — the touchstart
     * report alone was ASYNC and could land after the native ACTION_DOWN
     * already latched its armed decision (stale value from the previous
     * touch); SmartPull now re-checks on every ACTION_MOVE, so this live
     * feed is what disarms an in-progress pull on a scrolled chat page.
     * (No Kotlin interpolation: the JS never uses a dollar sign.)
     */
    const val SCROLL_GATE = """
        (function(){
          try{
            if(window.__zephyrScrollGate) return; window.__zephyrScrollGate=true;
            function pageUp(){
              try{
                var se=document.scrollingElement||document.documentElement;
                if((se&&se.scrollTop>0)||window.scrollY>0) return true;
                try{ if(document.body&&document.body.scrollTop>0) return true; }catch(e2){}
                return false;
              }catch(e){return false;}
            }
            function elUp(el){
              try{
                for(var n=el;n&&n.nodeType===1;n=n.parentElement){
                  if(n.scrollTop>0) return true;
                }
              }catch(e){}
              return false;
            }
            function rep(el){
              try{
                var up=pageUp()||elUp(el||document.body);
                if(window.Zephyr&&Zephyr.reportScroll) Zephyr.reportScroll(!!up);
              }catch(e){}
            }
            document.addEventListener('touchstart',function(e){try{rep(e&&e.target);}catch(x){}},{capture:true,passive:true});
            document.addEventListener('touchend',function(e){try{rep(e&&e.target);}catch(x){}},{capture:true,passive:true});
            var lastSR=0;
            document.addEventListener('scroll',function(){try{var n=Date.now();if(n-lastSR<100)return;lastSR=n;rep(document.body);}catch(e){}},{capture:true,passive:true});
            rep(document.body);
          }catch(e){}
        })();
    """
}
