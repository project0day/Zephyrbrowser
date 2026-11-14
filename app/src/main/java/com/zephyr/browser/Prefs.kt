package com.zephyr.browser

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "zephyr_prefs")

/** App preferences: DataStore persistence + synchronous snapshot for UI/chromium threads. */
object Prefs {

    private object K {
        val engine = stringPreferencesKey("engine_id")
        val customEngineName = stringPreferencesKey("custom_engine_name")
        val customEngineUrl = stringPreferencesKey("custom_engine_url")
        val theme = intPreferencesKey("theme_id")
        val homeBgColor = intPreferencesKey("home_bg_color")
        val wallpaperUri = stringPreferencesKey("wallpaper_uri")
        val homeQuick = booleanPreferencesKey("home_quick")
        val homeRecents = booleanPreferencesKey("home_recents")
        val homeBookmarks = booleanPreferencesKey("home_bookmarks")
        val homeSearchBar = booleanPreferencesKey("home_search_bar")
        val homeTileSize = intPreferencesKey("home_tile_size")          // 0 small 1 medium 2 large
        val homeGridColumns = intPreferencesKey("home_grid_columns")    // 4 | 5 | 6
        val payAutofill = booleanPreferencesKey("pay_autofill")
        val toolbarActions = stringPreferencesKey("toolbar_actions")
        val openInApps = intPreferencesKey("open_in_apps")              // 0 ask 1 never 2 always
        val appLinkAlways = stringSetPreferencesKey("applink_always")
        val appLinkNever = stringSetPreferencesKey("applink_never")
        val adBlockMode = intPreferencesKey("adblock_mode")             // 0 off 1 basic 2 balanced 3 strict
        val adBlockWhitelist = stringSetPreferencesKey("adblock_whitelist")
        val blockedTotal = longPreferencesKey("blocked_total")
        val blockedToday = longPreferencesKey("blocked_today")
        val blockedDay = intPreferencesKey("blocked_day")
        val jsEnabled = booleanPreferencesKey("js_enabled")
        val cookiesEnabled = booleanPreferencesKey("cookies_enabled")
        val thirdPartyCookies = booleanPreferencesKey("third_party_cookies")
        val imagesEnabled = booleanPreferencesKey("images_enabled")
        val popupsEnabled = booleanPreferencesKey("popups_enabled")
        val blockWebRTC = booleanPreferencesKey("block_webrtc")
        val alwaysIncognito = booleanPreferencesKey("always_incognito")
        val desktopDefault = booleanPreferencesKey("desktop_default")
        val restoreTabs = booleanPreferencesKey("restore_tabs")
        val pageAppearance = intPreferencesKey("page_appearance")       // 0 system 1 light 2 dark 3 sepia 4 gray 5 contrast
        val proxyType = intPreferencesKey("proxy_type")                 // 0 none 1 http 2 orbot 3 i2p
        val proxyHost = stringPreferencesKey("proxy_host")
        val proxyPort = intPreferencesKey("proxy_port")
        val language = stringPreferencesKey("language")                 // system|ru|en|fr
        val uaMode = intPreferencesKey("ua_mode")                       // 0 system 1 mobile 2 desktop 3 custom
        val customUa = stringPreferencesKey("custom_ua")
        val doNotTrack = booleanPreferencesKey("do_not_track")
        val pinHash = stringPreferencesKey("pin_hash")
        val pinSalt = stringPreferencesKey("pin_salt")
        val useBiometrics = booleanPreferencesKey("use_biometrics")
        val savePasswords = booleanPreferencesKey("save_passwords")
        val autofillEnabled = booleanPreferencesKey("autofill_enabled")
        // v1.4.2 FIX 7: per-site save modes — entries like "https://site.com=always" / "=never"
        val passwordSiteModes = stringSetPreferencesKey("password_site_modes")
        // v1.5.0: vault auto-lock (seconds; 0 = lock on leaving the panel) + custom accent color
        val vaultAutolock = intPreferencesKey("vault_autolock")
        val customAccent = intPreferencesKey("custom_accent")
        val clipboardClear = booleanPreferencesKey("clipboard_clear")
        // v1.7.5: edge-swipe navigation gestures + data backup
        val edgeGestures = booleanPreferencesKey("edge_gestures")
        val searchSuggestions = booleanPreferencesKey("search_suggestions")
        // v1.5.1: YouTube ad-block (JS skip/hide), bookmarks view mode (0 list 1 grid),
        // main-menu horizontal tile + vertical row ordering (comma-separated action ids)
        val youtubeAdBlock = booleanPreferencesKey("youtube_ad_block")
        // v1.7.2: SponsorBlock category toggles (which in-video segments to auto-skip)
        val sbSponsor = booleanPreferencesKey("sb_sponsor")
        val sbSelfpromo = booleanPreferencesKey("sb_selfpromo")
        val sbInteraction = booleanPreferencesKey("sb_interaction")
        val sbIntro = booleanPreferencesKey("sb_intro")
        val bmViewMode = intPreferencesKey("bm_view_mode")
        val menuTilesOrder = stringPreferencesKey("menu_tiles_order")
        val menuRowsOrder = stringPreferencesKey("menu_rows_order")
        val firstRunDone = booleanPreferencesKey("first_run_done")
        val saveTabs = stringPreferencesKey("save_tabs")
        // v1.7.0: built-in DNS selection + leak protection + VPN list sort
        val dnsProvider = intPreferencesKey("dns_provider")          // index into DnsCatalog (0 = system)
        val dnsCustomIp = stringPreferencesKey("dns_custom_ip")
        val dnsProtocol = intPreferencesKey("dns_protocol")          // 0 plain 1 DoT 2 DoH
        val dnsLeakProtection = booleanPreferencesKey("dns_leak_protection")
        val dnsPrivateMode = booleanPreferencesKey("dns_private_mode")
        val vpnSortMode = intPreferencesKey("vpn_sort_mode")         // 0 name 1 date
        // v1.7.6: pull-to-refresh + gesture shortcuts + night schedule + backup scope
        val pullToRefresh = booleanPreferencesKey("pull_to_refresh")
        val omniboxSwipeRefresh = booleanPreferencesKey("omnibox_swipe_refresh")
        val doubleTapScroll = booleanPreferencesKey("double_tap_scroll")
        val videoSniffer = booleanPreferencesKey("video_sniffer")
        val nightSchedule = booleanPreferencesKey("night_schedule")
        val nightStart = intPreferencesKey("night_start")             // minutes of day
        val nightEnd = intPreferencesKey("night_end")                 // minutes of day
        val nightActive = booleanPreferencesKey("night_active")       // schedule currently engaged
        val nightSavedTheme = intPreferencesKey("night_saved_theme")
        val nightSavedAppearance = intPreferencesKey("night_saved_appearance")
        val backupInclude = stringSetPreferencesKey("backup_include") // bm|quick|history|settings|vpn
        val autoBackup = booleanPreferencesKey("auto_backup")
        val lastBackupAt = longPreferencesKey("last_backup_at")
        // v1.7.6: one-time menu migration flag (offline rows appended for existing installs)
        val menuV176Done = booleanPreferencesKey("menu_v176_done")
        val menuV1754Done = booleanPreferencesKey("menu_v1754_done")
        val menuV1771Done = booleanPreferencesKey("menu_v1771_done")
        // v1.7.8: download manager + Files v2 + dismissable hint banners
        val downloadDirUri = stringPreferencesKey("download_dir_uri")
        val filesGrid = booleanPreferencesKey("files_grid")
        val filesSort = intPreferencesKey("files_sort")              // 0 name 1 new 2 old 3 size 4 type
        val dismissedHints = stringSetPreferencesKey("dismissed_hints")
        val menuV178Done = booleanPreferencesKey("menu_v178_done")
        val menuV1731Done = booleanPreferencesKey("menu_v1731_done")
        // v1.7.9: Files zoom (0 = крупные 2 колонки, 1 = 3 колонки, 2 = 4, 3 = 5 —
        // больше не нужно «масштабировать» только текстом), home feed direction,
        // download scheduler (Wi-Fi only / night-only window), payments gate
        val filesZoom = intPreferencesKey("files_zoom")               // 0..3
        val homeFeedMode = intPreferencesKey("home_feed_mode")        // 0 horizontal 1 vertical grid
        val dmWifiOnly = booleanPreferencesKey("dm_wifi_only")
        val dmNightOnly = booleanPreferencesKey("dm_night_only")
        val paymentsGate = booleanPreferencesKey("payments_gate")

        // v1.7.14: user-reorderable Files tabs (stored as CSV of tab ids, e.g.
        // "2,0,1" = Память first) + home page variant (0 standard, 1 minimal,
        // 2 clock, 3 sites grid, 4 custom page) and the custom page URL/path.
        val filesTabOrder = stringPreferencesKey("files_tab_order")   // "0,1,2" default
        val homeVariant = intPreferencesKey("home_variant")            // 0..4
        val homeCustomUrl = stringPreferencesKey("home_custom_url")
        // v1.7.24: bottom search bar + AI Assistant settings
        val bottomSearchBar = booleanPreferencesKey("bottom_search_bar")
        val aiApiKey = stringPreferencesKey("ai_api_key")
        val aiEndpoint = stringPreferencesKey("ai_endpoint")
        val aiModel = stringPreferencesKey("ai_model")
        val aiPrompt = stringPreferencesKey("ai_prompt")
        // v1.7.25: multi-model support — JSON array of AI profiles + active index
        val aiProfiles = stringPreferencesKey("ai_profiles")
        val aiActiveProfile = intPreferencesKey("ai_active_profile")
        // v1.7.27: generation tuning + usage readout
        val aiTemperature = intPreferencesKey("ai_temperature")     // 0..200 = 0.00..2.00
        val aiMaxTokens = intPreferencesKey("ai_max_tokens")
        val aiHistoryDepth = intPreferencesKey("ai_history_depth")  // turns sent back
        val aiShowUsage = booleanPreferencesKey("ai_show_usage")
        val aiStreaming = booleanPreferencesKey("ai_streaming")
        val aiSendPageContext = booleanPreferencesKey("ai_send_page_context")
        val aiWebAccess = booleanPreferencesKey("ai_web_access")
        // v1.7.33: remote agent pairing
        val agentCode = stringPreferencesKey("agent_code")
        val agentServer = stringPreferencesKey("agent_server")
        val agentTimeoutSec = intPreferencesKey("agent_timeout_sec")
        val agentFallback = booleanPreferencesKey("agent_fallback")
        // v1.7.36: swarm — which profiles answer, and how they cooperate
        val swarmEnabled = booleanPreferencesKey("swarm_enabled")
        val swarmMembers = stringSetPreferencesKey("swarm_members")
        val swarmMode = intPreferencesKey("swarm_mode")
        // v1.7.41: what the assistant is allowed to DO
        val aiToolsPage = booleanPreferencesKey("ai_tools_page")
        val aiToolsBrowser = booleanPreferencesKey("ai_tools_browser")
        val aiToolsData = booleanPreferencesKey("ai_tools_data")
        val aiTrustActions = booleanPreferencesKey("ai_trust_actions")
        val aiToolsModels = booleanPreferencesKey("ai_tools_models")
        // v1.7.54: writing code + tasks
        val aiToolsWrite = booleanPreferencesKey("ai_tools_write")
        val aiWriteAnywhere = booleanPreferencesKey("ai_write_anywhere")
        val aiToolsTasks = booleanPreferencesKey("ai_tools_tasks")
        /** v1.7.55: how many months the Дела calendar shows at once. */
        val tasksSpan = intPreferencesKey("tasks_span")
        /** v1.7.55: what the burn button wipes, and whether it asks first. */
        val burnTargets = stringSetPreferencesKey("burn_targets")
        val burnConfirm = booleanPreferencesKey("burn_confirm")
        /** v1.7.55: speech-to-text through an OpenAI-compatible provider. */
        val sttEndpoint = stringPreferencesKey("stt_endpoint")
        val sttModel = stringPreferencesKey("stt_model")
        val sttKey = stringPreferencesKey("stt_key")
        /**
         * v1.7.79: which recogniser the mic button uses —
         * "auto" (system → offline → cloud), "system", "offline", "cloud".
         */
        val voiceEngine = stringPreferencesKey("voice_engine")
        /** Language code of the downloaded offline model in use. */
        val voiceLang = stringPreferencesKey("voice_lang")
        /** v1.7.80: announce a finished answer while the app is backgrounded. */
        val aiReplyNotify = booleanPreferencesKey("ai_reply_notify")
        /**
         * v1.7.84: fetch site icons from a third-party icon service.
         *
         * Improves how tabs and bookmarks look, at the cost of telling that
         * service which domains are being opened. On by default, never used in
         * private tabs.
         */
        val faviconNetwork = booleanPreferencesKey("favicon_network")
        /** v1.7.57: durable facts the assistant carries between chats. */
        val aiMemory = stringPreferencesKey("ai_memory")
        val aiMemoryOn = booleanPreferencesKey("ai_memory_on")
        /** v1.7.60: the assistant may write and install userscripts. */
        val aiToolsScripts = booleanPreferencesKey("ai_tools_scripts")
        /** v1.7.61: the user own saved commands. See AiWorkflows. */
        val aiWorkflows = stringPreferencesKey("ai_workflows")
        /** v1.7.70: the user business details and document numbering. */
        val aiBusiness = stringPreferencesKey("ai_business")
        val aiDocNumbers = stringPreferencesKey("ai_doc_numbers")
        /** v1.7.73: emoji and colour per template group. */
        val aiTemplateGroups = stringPreferencesKey("ai_template_groups")
        /** v1.7.53: profiles whose provider rejected the reasoning params. */
        val aiNoThinking = stringSetPreferencesKey("ai_no_thinking")
    }

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- synchronous snapshot ----
    var engineId: String = "google"; private set
    var customEngineName: String = ""; private set
    var customEngineUrl: String = ""; private set
    var themeId: Int = 0; private set
    var homeBgColor: Int = 0; private set           // 0 = none
    var wallpaperUri: String = ""; private set
    var homeQuick: Boolean = true; private set
    var homeRecents: Boolean = true; private set
    var homeBookmarks: Boolean = true; private set
    var homeSearchBar: Boolean = true; private set
    var homeTileSize: Int = 1; private set
    var homeGridColumns: Int = 4; private set
    var payAutofill: Boolean = true; private set
    var toolbarActions: String = "bookmark"; private set
    var openInApps: Int = 0; private set
    var appLinkAlways: Set<String> = emptySet(); private set
    var appLinkNever: Set<String> = emptySet(); private set
    var adBlockMode: Int = 2; private set
    var adBlockWhitelist: Set<String> = emptySet(); private set
    var blockedTotal: Long = 0; private set
    var blockedToday: Long = 0; private set
    var blockedDay: Int = -1; private set
    var jsEnabled: Boolean = true; private set
    var cookiesEnabled: Boolean = true; private set
    var thirdPartyCookies: Boolean = true; private set
    var imagesEnabled: Boolean = true; private set
    var popupsEnabled: Boolean = true; private set
    var blockWebRTC: Boolean = false; private set
    var alwaysIncognito: Boolean = false; private set
    var desktopDefault: Boolean = false; private set
    var restoreTabs: Boolean = true; private set
    var pageAppearance: Int = 0; private set
    var proxyType: Int = 0; private set
    var proxyHost: String = ""; private set
    var proxyPort: Int = 8118; private set
    var language: String = "system"; private set
    var uaMode: Int = 0; private set                 // 0 system 1 mobile 2 desktop 3 custom
    var customUa: String = ""; private set
    var doNotTrack: Boolean = false; private set
    var pinHash: String = ""; private set
    var pinSalt: String = ""; private set
    var useBiometrics: Boolean = false; private set
    var savePasswords: Boolean = true; private set
    var autofillEnabled: Boolean = true; private set
    // v1.4.2 FIX 7: origin → "always" (silent save) / "never" (no prompts); unset = ask
    var passwordSiteModes: Set<String> = emptySet(); private set
    // v1.5.0: vault auto-lock seconds (0 = on panel exit) and custom accent (0 = theme default)
    var vaultAutolock: Int = 60; private set
    var customAccent: Int = 0; private set
    var clipboardClear: Boolean = true; private set
    // v1.7.5: edge-swipe back/forward navigation (on by default)
    var edgeGestures: Boolean = true; private set
    var searchSuggestions: Boolean = true; private set
    // v1.5.1
    var youtubeAdBlock: Boolean = true; private set
    var sbSponsor: Boolean = true; private set
    var sbSelfpromo: Boolean = true; private set
    var sbInteraction: Boolean = false; private set
    var sbIntro: Boolean = false; private set
    var bmViewMode: Int = 0; private set          // 0 list, 1 grid
    var menuTilesOrder: String = "newtab,private,bookmarks,history,downloads"; private set
    // v1.6.0: +vpn, screenshot, pdf in the default row order (13 more available in the menu editor)
    // v1.7.0: +dns row (built-in DNS screen) after vpn
    // v1.7.3: +addquick ("add to quick access" home-screen tile) after addbookmark
    var menuRowsOrder: String = "find,desktop,translate,reader,appearance,addbookmark,addquick,saveoffline,addhomescreen,screenshot,pdf,share,copylink,external,video,floating,ai,camai,tasks,templates,workspace,workflows,burn,adblock,vpn,clearsite,history,downloads,bookmarks,closeall,settings"; private set
    var firstRunDone: Boolean = false; private set
    var saveTabs: String = ""; private set
    // v1.7.0: DNS + VPN list state
    var dnsProvider: Int = 0; private set             // 0 system, 1..10 catalog, 11 custom
    var dnsCustomIp: String = ""; private set
    var dnsProtocol: Int = 1; private set             // 0 plain 1 DoT 2 DoH
    var dnsLeakProtection: Boolean = true; private set
    var dnsPrivateMode: Boolean = false; private set  // DNS-only VPN active preference
    var vpnSortMode: Int = 0; private set             // 0 by name, 1 by date added
    // v1.7.6: pull-to-refresh / gesture shortcuts / night schedule / backup scope
    var pullToRefresh: Boolean = true; private set
    var omniboxSwipeRefresh: Boolean = true; private set
    var doubleTapScroll: Boolean = true; private set
    // v1.7.7: media sniffer (floating «Скачать видео» pill when a player is detected)
    var videoSniffer: Boolean = true; private set
    // v1.7.8: download manager + Files v2 + dismissable hints
    var downloadDirUri: String = ""; private set
    var filesGrid: Boolean = true; private set
    var filesSort: Int = 1; private set
    // v1.7.9
    var filesZoom: Int = 0; private set            // 0..3 → grid span 2..5 / list text scale
    var homeFeedMode: Int = 0; private set         // 0 horizontal 1 vertical grid
    var dmWifiOnly: Boolean = false; private set
    var dmNightOnly: Boolean = false; private set
    var paymentsGate: Boolean = false; private set

    // v1.7.14
    var filesTabOrder: String = "0,1,2"; private set  // CSV of Files tab ids
    var homeVariant: Int = 0; private set             // 0 std, 1 minimal, 2 clock, 3 grid, 4 custom
    var homeCustomUrl: String = ""; private set
    var dismissedHints: Set<String> = emptySet(); private set
    var nightSchedule: Boolean = false; private set
    var nightStart: Int = 1320; private set          // 22:00
    var nightEnd: Int = 360; private set             // 06:00
    var nightActive: Boolean = false; private set
    var nightSavedTheme: Int = 0; private set
    var nightSavedAppearance: Int = 0; private set
    var backupInclude: Set<String> = setOf("bm", "quick", "history", "settings", "vpn"); private set
    var autoBackup: Boolean = false; private set
    var lastBackupAt: Long = 0; private set
    // v1.7.24: bottom search bar + AI Assistant settings
    var bottomSearchBar: Boolean = false; private set
    var aiApiKey: String = ""; private set
    var aiEndpoint: String = "https://api.openai.com/v1/chat/completions"; private set
    var aiModel: String = "gpt-4o-mini"; private set
    var aiPrompt: String = "You are Zephyr AI Assistant, a helpful, fast, and concise web browsing companion."; private set
    // v1.7.25: saved AI model profiles (JSON array) + index of the active one
    var aiProfiles: String = ""; private set
    var aiActiveProfile: Int = 0; private set
    // v1.7.27: generation tuning + usage readout
    var aiTemperature: Int = 70; private set          // 0.70
    var aiMaxTokens: Int = 2048; private set
    var aiHistoryDepth: Int = 20; private set
    var aiShowUsage: Boolean = true; private set
    var aiStreaming: Boolean = true; private set
    var aiSendPageContext: Boolean = true; private set
    /** v1.7.30: let the assistant search the web and read pages. */
    var aiWebAccess: Boolean = true; private set
    // v1.7.33: remote agent pairing
    var agentCode: String = ""; private set
    var agentServer: String = "https://ntfy.sh"; private set
    var agentTimeoutSec: Int = 120; private set
    var agentFallback: Boolean = true; private set
    // v1.7.36: swarm
    var swarmEnabled: Boolean = false; private set
    var swarmMembers: Set<String> = emptySet(); private set
    /** 0 = chain (first who answers), 1 = council (everyone answers). */
    var swarmMode: Int = 0; private set
    // v1.7.41: assistant capabilities
    var aiToolsPage: Boolean = true; private set
    var aiToolsBrowser: Boolean = true; private set
    var aiToolsData: Boolean = true; private set
    /** Skip the per-action confirmation. Off by default, on purpose. */
    var aiTrustActions: Boolean = false; private set
    /** v1.7.42: let the assistant add/switch its own model profiles. */
    var aiToolsModels: Boolean = true; private set
    /** v1.7.54: create folders and write files in the assistant workspace. */
    var aiToolsWrite: Boolean = true; private set
    /** Let those writes leave Documents/Zephyr. Off by default, on purpose. */
    var aiWriteAnywhere: Boolean = false; private set
    /** v1.7.54: create and manage reminders. */
    var aiToolsTasks: Boolean = true; private set
    /** 1, 3, 6 or 12 months on one page. */
    var tasksSpan: Int = 1; private set
    /** Empty means «never configured» — BurnOptions falls back to its defaults. */
    var burnTargets: Set<String> = emptySet(); private set
    var burnConfirm: Boolean = true; private set
    /** Blank means «derive from the active AI profile» — see AiVoice.endpoint(). */
    var sttEndpoint: String = ""; private set
    var sttModel: String = ""; private set
    var sttKey: String = ""; private set
    var voiceEngine: String = "auto"; private set
    var voiceLang: String = ""; private set
    var aiReplyNotify: Boolean = true; private set
    var faviconNetwork: Boolean = true; private set
    /** JSON array of {id, t, at}. See AiMemory. */
    var aiMemory: String = ""; private set
    var aiMemoryOn: Boolean = true; private set
    var aiToolsScripts: Boolean = true; private set
    var aiWorkflows: String = ""; private set
    var aiBusiness: String = ""; private set
    var aiDocNumbers: String = ""; private set
    var aiTemplateGroups: String = ""; private set
    var aiNoThinking: Set<String> = emptySet(); private set

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        scope.launch { reload() }
    }

    private suspend fun reload() {
        appContext.dataStore.data.collect { p ->
            engineId = p[K.engine] ?: "google"
            customEngineName = p[K.customEngineName] ?: ""
            customEngineUrl = p[K.customEngineUrl] ?: ""
            themeId = p[K.theme] ?: 0
            homeBgColor = p[K.homeBgColor] ?: 0
            wallpaperUri = p[K.wallpaperUri] ?: ""
            homeQuick = p[K.homeQuick] ?: true
            homeRecents = p[K.homeRecents] ?: true
            homeBookmarks = p[K.homeBookmarks] ?: true
            homeSearchBar = p[K.homeSearchBar] ?: true
            homeTileSize = p[K.homeTileSize] ?: 1
            homeGridColumns = p[K.homeGridColumns] ?: 4
            payAutofill = p[K.payAutofill] ?: true
            toolbarActions = p[K.toolbarActions] ?: "bookmark"
            openInApps = p[K.openInApps] ?: 0
            appLinkAlways = p[K.appLinkAlways] ?: emptySet()
            appLinkNever = p[K.appLinkNever] ?: emptySet()
            adBlockMode = p[K.adBlockMode] ?: 2
            adBlockWhitelist = p[K.adBlockWhitelist] ?: emptySet()
            blockedTotal = p[K.blockedTotal] ?: 0L
            blockedToday = p[K.blockedToday] ?: 0L
            blockedDay = p[K.blockedDay] ?: -1
            jsEnabled = p[K.jsEnabled] ?: true
            cookiesEnabled = p[K.cookiesEnabled] ?: true
            thirdPartyCookies = p[K.thirdPartyCookies] ?: true
            imagesEnabled = p[K.imagesEnabled] ?: true
            popupsEnabled = p[K.popupsEnabled] ?: true
            blockWebRTC = p[K.blockWebRTC] ?: false
            alwaysIncognito = p[K.alwaysIncognito] ?: false
            desktopDefault = p[K.desktopDefault] ?: false
            restoreTabs = p[K.restoreTabs] ?: true
            pageAppearance = p[K.pageAppearance] ?: 0
            proxyType = p[K.proxyType] ?: 0
            proxyHost = p[K.proxyHost] ?: ""
            proxyPort = p[K.proxyPort] ?: 8118
            language = p[K.language] ?: "system"
            uaMode = p[K.uaMode] ?: 0
            customUa = p[K.customUa] ?: ""
            doNotTrack = p[K.doNotTrack] ?: false
            pinHash = p[K.pinHash] ?: ""
            pinSalt = p[K.pinSalt] ?: ""
            useBiometrics = p[K.useBiometrics] ?: false
            savePasswords = p[K.savePasswords] ?: true
            autofillEnabled = p[K.autofillEnabled] ?: true
            passwordSiteModes = p[K.passwordSiteModes] ?: emptySet()
            vaultAutolock = p[K.vaultAutolock] ?: 60
            customAccent = p[K.customAccent] ?: 0
            clipboardClear = p[K.clipboardClear] ?: true
            edgeGestures = p[K.edgeGestures] ?: true
            searchSuggestions = p[K.searchSuggestions] ?: true
            youtubeAdBlock = p[K.youtubeAdBlock] ?: true
            sbSponsor = p[K.sbSponsor] ?: true
            sbSelfpromo = p[K.sbSelfpromo] ?: true
            sbInteraction = p[K.sbInteraction] ?: false
            sbIntro = p[K.sbIntro] ?: false
            bmViewMode = p[K.bmViewMode] ?: 0
            menuTilesOrder = p[K.menuTilesOrder] ?: "newtab,private,bookmarks,history,downloads"
            menuRowsOrder = p[K.menuRowsOrder] ?: "find,desktop,translate,reader,appearance,addbookmark,addquick,saveoffline,addhomescreen,screenshot,pdf,share,copylink,external,video,floating,ai,camai,tasks,templates,workspace,workflows,burn,adblock,vpn,clearsite,history,downloads,bookmarks,closeall,settings"
            // v1.7.3: one-time migration — surface the new "add to quick access" row for
            // existing installs (mutate() launches on the IO scope, same as every setter).
            if (!menuRowsOrder.contains("addquick")) {
                menuRowsOrder = if (menuRowsOrder.isEmpty()) "addquick" else menuRowsOrder + ",addquick"
                mutate(K.menuRowsOrder, menuRowsOrder)
            }
            // v1.7.6: one-time — surface the offline-reading rows for existing installs
            // (flag-guarded, unlike addquick: users who delete the rows keep them deleted).
            if (p[K.menuV176Done] != true) {
                if (!menuRowsOrder.contains("saveoffline")) {
                    menuRowsOrder = if (menuRowsOrder.isEmpty()) "saveoffline,offline"
                    else menuRowsOrder + ",saveoffline,offline"
                }
                mutate(K.menuRowsOrder, menuRowsOrder)
                mutate(K.menuV176Done, true)
            }
            // v1.7.54: one-time — surface «Дела и напоминания» for existing installs
            if (p[K.menuV1754Done] != true) {
                if (!menuRowsOrder.contains("tasks")) {
                    menuRowsOrder = if (menuRowsOrder.isEmpty()) "tasks"
                    else if (menuRowsOrder.contains(",settings")) menuRowsOrder.replace(",settings", ",tasks,settings")
                    else menuRowsOrder + ",tasks"
                }
                mutate(K.menuRowsOrder, menuRowsOrder)
                mutate(K.menuV1754Done, true)
            }
            // v1.7.71: one-time — surface Шаблоны, Проекты and Сценарии. They
            // were registered but absent from the default order, so they existed
            // only for someone who went looking in Настройки → Меню.
            if (p[K.menuV1771Done] != true) {
                for (id in listOf("templates", "workspace", "workflows", "camai")) {
                    if (!menuRowsOrder.contains(id)) {
                        menuRowsOrder = if (menuRowsOrder.isEmpty()) id
                        else if (menuRowsOrder.contains(",settings")) menuRowsOrder.replace(",settings", ",,settings")
                        else menuRowsOrder + ","
                    }
                }
                mutate(K.menuRowsOrder, menuRowsOrder)
                mutate(K.menuV1771Done, true)
            }
            // v1.7.8: one-time — surface the «Видео со страницы» row for existing installs
            if (p[K.menuV178Done] != true) {
                if (!menuRowsOrder.contains("video")) {
                    menuRowsOrder = if (menuRowsOrder.isEmpty()) "video"
                    else if (menuRowsOrder.contains(",settings,")) menuRowsOrder.replace(",settings,", ",video,settings,")
                    else menuRowsOrder + ",video"
                }
                mutate(K.menuRowsOrder, menuRowsOrder)
                mutate(K.menuV178Done, true)
            }
            // v1.7.31: one-time — surface the ad blocker for existing installs
            // (user: «в холодный старт добавь Блокировщик рекламы в попап меню,
            // это важно»; a fresh install already gets it from the default).
            if (p[K.menuV1731Done] != true) {
                if (!menuRowsOrder.contains("adblock")) {
                    menuRowsOrder = when {
                        menuRowsOrder.isEmpty() -> "adblock"
                        menuRowsOrder.contains(",settings") ->
                            menuRowsOrder.replace(",settings", ",adblock,settings")
                        else -> "$menuRowsOrder,adblock"
                    }
                    mutate(K.menuRowsOrder, menuRowsOrder)
                }
                mutate(K.menuV1731Done, true)
            }
            downloadDirUri = p[K.downloadDirUri] ?: ""
            filesGrid = p[K.filesGrid] ?: true
            filesSort = p[K.filesSort] ?: 1
            // v1.7.9
            filesZoom = p[K.filesZoom] ?: 0
            homeFeedMode = p[K.homeFeedMode] ?: 0
            dmWifiOnly = p[K.dmWifiOnly] ?: false
            dmNightOnly = p[K.dmNightOnly] ?: false
            paymentsGate = p[K.paymentsGate] ?: false
            filesTabOrder = p[K.filesTabOrder] ?: "0,1,2"
            homeVariant = (p[K.homeVariant] ?: 0).coerceIn(0, 4)
            homeCustomUrl = p[K.homeCustomUrl] ?: ""
            dismissedHints = p[K.dismissedHints] ?: emptySet()
            firstRunDone = p[K.firstRunDone] ?: false
            saveTabs = p[K.saveTabs] ?: ""
            dnsProvider = p[K.dnsProvider] ?: 0
            dnsCustomIp = p[K.dnsCustomIp] ?: ""
            dnsProtocol = p[K.dnsProtocol] ?: 1
            dnsLeakProtection = p[K.dnsLeakProtection] ?: true
            dnsPrivateMode = p[K.dnsPrivateMode] ?: false
            vpnSortMode = p[K.vpnSortMode] ?: 0
            pullToRefresh = p[K.pullToRefresh] ?: true
            omniboxSwipeRefresh = p[K.omniboxSwipeRefresh] ?: true
            doubleTapScroll = p[K.doubleTapScroll] ?: true
            videoSniffer = p[K.videoSniffer] ?: true
            nightSchedule = p[K.nightSchedule] ?: false
            nightStart = p[K.nightStart] ?: 1320
            nightEnd = p[K.nightEnd] ?: 360
            nightActive = p[K.nightActive] ?: false
            nightSavedTheme = p[K.nightSavedTheme] ?: 0
            nightSavedAppearance = p[K.nightSavedAppearance] ?: 0
            backupInclude = p[K.backupInclude] ?: setOf("bm", "quick", "history", "settings", "vpn")
            autoBackup = p[K.autoBackup] ?: false
            lastBackupAt = p[K.lastBackupAt] ?: 0L
            bottomSearchBar = p[K.bottomSearchBar] ?: false
            aiApiKey = p[K.aiApiKey] ?: ""
            aiEndpoint = p[K.aiEndpoint] ?: "https://api.openai.com/v1/chat/completions"
            aiModel = p[K.aiModel] ?: "gpt-4o-mini"
            aiPrompt = p[K.aiPrompt] ?: "You are Zephyr AI Assistant, a helpful, fast, and concise web browsing companion."
            aiProfiles = p[K.aiProfiles] ?: ""
            aiActiveProfile = p[K.aiActiveProfile] ?: 0
            aiTemperature = p[K.aiTemperature] ?: 70
            aiMaxTokens = p[K.aiMaxTokens] ?: 2048
            aiHistoryDepth = p[K.aiHistoryDepth] ?: 20
            aiShowUsage = p[K.aiShowUsage] ?: true
            aiStreaming = p[K.aiStreaming] ?: true
            aiSendPageContext = p[K.aiSendPageContext] ?: true
            aiWebAccess = p[K.aiWebAccess] ?: true
            agentCode = p[K.agentCode] ?: ""
            agentServer = p[K.agentServer] ?: "https://ntfy.sh"
            agentTimeoutSec = p[K.agentTimeoutSec] ?: 120
            agentFallback = p[K.agentFallback] ?: true
            swarmEnabled = p[K.swarmEnabled] ?: false
            swarmMembers = p[K.swarmMembers] ?: emptySet()
            swarmMode = p[K.swarmMode] ?: 0
            aiToolsPage = p[K.aiToolsPage] ?: true
            aiToolsBrowser = p[K.aiToolsBrowser] ?: true
            aiToolsData = p[K.aiToolsData] ?: true
            aiTrustActions = p[K.aiTrustActions] ?: false
            aiToolsModels = p[K.aiToolsModels] ?: true
            aiToolsWrite = p[K.aiToolsWrite] ?: true
            aiWriteAnywhere = p[K.aiWriteAnywhere] ?: false
            aiToolsTasks = p[K.aiToolsTasks] ?: true
            tasksSpan = p[K.tasksSpan] ?: 1
            burnTargets = p[K.burnTargets] ?: emptySet()
            burnConfirm = p[K.burnConfirm] ?: true
            sttEndpoint = p[K.sttEndpoint] ?: ""
            sttModel = p[K.sttModel] ?: ""
            sttKey = p[K.sttKey] ?: ""
            voiceEngine = p[K.voiceEngine] ?: "auto"
            voiceLang = p[K.voiceLang] ?: ""
            aiReplyNotify = p[K.aiReplyNotify] ?: true
            faviconNetwork = p[K.faviconNetwork] ?: true
            aiMemory = p[K.aiMemory] ?: ""
            aiMemoryOn = p[K.aiMemoryOn] ?: true
            aiToolsScripts = p[K.aiToolsScripts] ?: true
            aiWorkflows = p[K.aiWorkflows] ?: ""
            aiBusiness = p[K.aiBusiness] ?: ""
            aiDocNumbers = p[K.aiDocNumbers] ?: ""
            aiTemplateGroups = p[K.aiTemplateGroups] ?: ""
            aiNoThinking = p[K.aiNoThinking] ?: emptySet()
        }
    }

    fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        scope.launch { appContext.dataStore.edit(block) }
    }

    // convenience setters that also update the snapshot instantly for UI reactivity
    private fun <T> mutate(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        edit { it[key] = value }
    }

    fun setEngine(id: String) { engineId = id; mutate(K.engine, id) }
    fun setCustomEngine(name: String, url: String) { customEngineName = name; customEngineUrl = url; edit { it[K.customEngineName] = name; it[K.customEngineUrl] = url } }
    fun setTheme(id: Int) {
        themeId = id
        mutate(K.theme, id)
        // synchronous mirror: valid at cold start before DataStore warms up
        try {
            appContext.getSharedPreferences("zephyr_ui", Context.MODE_PRIVATE)
                .edit().putInt("theme_id", id).apply()
        } catch (_: Exception) {}
    }

    /** Synchronous theme read valid in onCreate before DataStore emission (cold-start fix). */
    fun themeSnapshot(context: Context): Int = try {
        context.getSharedPreferences("zephyr_ui", Context.MODE_PRIVATE)
            .getInt("theme_id", 0)
    } catch (_: Exception) { 0 }
    fun setHomeBgColor(color: Int) { homeBgColor = color; mutate(K.homeBgColor, color) }
    fun setWallpaper(uri: String) { wallpaperUri = uri; mutate(K.wallpaperUri, uri) }
    fun setToolbarActions(actions: String) { toolbarActions = actions; mutate(K.toolbarActions, actions) }
    fun setOpenInApps(mode: Int) { openInApps = mode; mutate(K.openInApps, mode) }
    fun toggleAppLinkAlways(host: String, on: Boolean) {
        appLinkAlways = if (on) appLinkAlways + host else appLinkAlways - host
        mutate(K.appLinkAlways, appLinkAlways)
    }
    fun toggleAppLinkNever(host: String, on: Boolean) {
        appLinkNever = if (on) appLinkNever + host else appLinkNever - host
        mutate(K.appLinkNever, appLinkNever)
    }
    fun setAdBlockMode(mode: Int) { adBlockMode = mode; mutate(K.adBlockMode, mode) }
    fun toggleWhitelist(domain: String, on: Boolean) {
        adBlockWhitelist = if (on) adBlockWhitelist + domain else adBlockWhitelist - domain
        mutate(K.adBlockWhitelist, adBlockWhitelist)
    }
    fun setJs(on: Boolean) { jsEnabled = on; mutate(K.jsEnabled, on) }
    fun setCookies(on: Boolean) { cookiesEnabled = on; mutate(K.cookiesEnabled, on) }
    fun setThirdPartyCookies(on: Boolean) { thirdPartyCookies = on; mutate(K.thirdPartyCookies, on) }
    fun setImages(on: Boolean) { imagesEnabled = on; mutate(K.imagesEnabled, on) }
    fun setPopups(on: Boolean) { popupsEnabled = on; mutate(K.popupsEnabled, on) }
    fun setBlockWebRTC(on: Boolean) { blockWebRTC = on; mutate(K.blockWebRTC, on) }
    fun setAlwaysIncognito(on: Boolean) { alwaysIncognito = on; mutate(K.alwaysIncognito, on) }
    fun setDesktopDefault(on: Boolean) { desktopDefault = on; mutate(K.desktopDefault, on) }
    fun setRestoreTabs(on: Boolean) { restoreTabs = on; mutate(K.restoreTabs, on) }
    fun setPageAppearance(a: Int) { pageAppearance = a; mutate(K.pageAppearance, a) }
    fun setProxy(type: Int, host: String, port: Int) {
        proxyType = type; proxyHost = host; proxyPort = port
        edit { it[K.proxyType] = type; it[K.proxyHost] = host; it[K.proxyPort] = port }
    }
    fun setLanguage(tag: String) {
        language = tag
        mutate(K.language, tag)
        // synchronous mirror for attachBaseContext (read before DataStore warms up)
        try {
            appContext.getSharedPreferences("zephyr_lang", Context.MODE_PRIVATE).edit().putString("lang", tag).apply()
        } catch (_: Exception) {}
    }

    /** Synchronous language read valid during attachBaseContext. */
    fun languageSnapshot(context: Context): String = try {
        context.getSharedPreferences("zephyr_lang", Context.MODE_PRIVATE).getString("lang", "system") ?: "system"
    } catch (_: Exception) { "system" }
    fun setUaMode(mode: Int) { uaMode = mode; mutate(K.uaMode, mode) }
    fun setCustomUa(ua: String) { customUa = ua; mutate(K.customUa, ua) }
    fun setDoNotTrack(on: Boolean) { doNotTrack = on; mutate(K.doNotTrack, on) }

    fun setPin(hash: String, salt: String) { pinHash = hash; pinSalt = salt; edit { it[K.pinHash] = hash; it[K.pinSalt] = salt } }
    fun setUseBiometrics(on: Boolean) { useBiometrics = on; mutate(K.useBiometrics, on) }
    fun setSavePasswords(on: Boolean) { savePasswords = on; mutate(K.savePasswords, on) }
    fun setAutofill(on: Boolean) { autofillEnabled = on; mutate(K.autofillEnabled, on) }
    // v1.5.1
    fun setYoutubeAdBlock(on: Boolean) { youtubeAdBlock = on; mutate(K.youtubeAdBlock, on) }
    fun setSbSponsor(on: Boolean) { sbSponsor = on; mutate(K.sbSponsor, on) }
    fun setSbSelfpromo(on: Boolean) { sbSelfpromo = on; mutate(K.sbSelfpromo, on) }
    fun setSbInteraction(on: Boolean) { sbInteraction = on; mutate(K.sbInteraction, on) }
    fun setSbIntro(on: Boolean) { sbIntro = on; mutate(K.sbIntro, on) }
    fun setBmViewMode(mode: Int) { bmViewMode = mode; mutate(K.bmViewMode, mode) }
    fun setMenuTilesOrder(order: String) { menuTilesOrder = order; mutate(K.menuTilesOrder, order) }
    fun setMenuRowsOrder(order: String) { menuRowsOrder = order; mutate(K.menuRowsOrder, order) }
    // v1.7.0 ------------------------------------------------------------------
    fun setDnsProvider(id: Int) { dnsProvider = id; mutate(K.dnsProvider, id) }
    fun setDnsCustomIp(ip: String) { dnsCustomIp = ip; mutate(K.dnsCustomIp, ip) }
    fun setDnsProtocol(p: Int) { dnsProtocol = p; mutate(K.dnsProtocol, p) }
    fun setDnsLeakProtection(on: Boolean) { dnsLeakProtection = on; mutate(K.dnsLeakProtection, on) }
    fun setDnsPrivateMode(on: Boolean) { dnsPrivateMode = on; mutate(K.dnsPrivateMode, on) }
    fun setVpnSortMode(mode: Int) { vpnSortMode = mode; mutate(K.vpnSortMode, mode) }

    /** v1.4.2 FIX 7: save-mode for a site: "always" | "never" | null (= ask every time). */
    fun passwordSiteMode(origin: String): String? =
        passwordSiteModes.firstOrNull { it.startsWith("$origin=") }?.substringAfter('=')

    fun setPasswordSiteMode(origin: String, mode: String) {
        passwordSiteModes = (passwordSiteModes.filter { !it.startsWith("$origin=") } + "$origin=$mode").toSet()
        mutate(K.passwordSiteModes, passwordSiteModes)
    }
    fun setClipboardClear(on: Boolean) { clipboardClear = on; mutate(K.clipboardClear, on) }
    fun setEdgeGestures(on: Boolean) { edgeGestures = on; mutate(K.edgeGestures, on) }

    // v1.5.0 ------------------------------------------------------------------

    /** Vault auto-lock delay in seconds: 0 = lock as soon as the panel closes. */
    fun setVaultAutolock(seconds: Int) { vaultAutolock = seconds; mutate(K.vaultAutolock, seconds) }

    /** Custom accent color (0 = use the theme's default accent). */
    fun setCustomAccent(color: Int) { customAccent = color; mutate(K.customAccent, color) }

    /** Custom accent when set, otherwise the theme-provided default. */
    fun accentOrDefault(default: Int): Int = if (customAccent != 0) customAccent else default

    fun setSearchSuggestions(on: Boolean) { searchSuggestions = on; mutate(K.searchSuggestions, on) }
    fun setFirstRunDone() { firstRunDone = true; mutate(K.firstRunDone, true) }
    fun setSaveTabs(json: String) { saveTabs = json; mutate(K.saveTabs, json) }
    fun setHomeToggles(quick: Boolean, recents: Boolean, bookmarks: Boolean) {
        homeQuick = quick; homeRecents = recents; homeBookmarks = bookmarks
        edit { it[K.homeQuick] = quick; it[K.homeRecents] = recents; it[K.homeBookmarks] = bookmarks }
    }
    fun setHomeSearchBar(on: Boolean) { homeSearchBar = on; mutate(K.homeSearchBar, on) }
    fun setBottomSearchBar(on: Boolean) { bottomSearchBar = on; mutate(K.bottomSearchBar, on) }
    fun setAiSettings(key: String, endpoint: String, model: String, prompt: String) {
        aiApiKey = key; aiEndpoint = endpoint; aiModel = model; aiPrompt = prompt
        edit {
            it[K.aiApiKey] = key
            it[K.aiEndpoint] = endpoint
            it[K.aiModel] = model
            it[K.aiPrompt] = prompt
        }
    }
    /** v1.7.27: AI generation tuning + readout toggles. */
    fun setAiTemperature(v: Int) { aiTemperature = v.coerceIn(0, 200); mutate(K.aiTemperature, aiTemperature) }
    fun setAiMaxTokens(v: Int) { aiMaxTokens = v.coerceIn(256, 32768); mutate(K.aiMaxTokens, aiMaxTokens) }
    fun setAiHistoryDepth(v: Int) { aiHistoryDepth = v.coerceIn(2, 100); mutate(K.aiHistoryDepth, aiHistoryDepth) }
    fun setAiShowUsage(on: Boolean) { aiShowUsage = on; mutate(K.aiShowUsage, on) }
    fun setAiStreaming(on: Boolean) { aiStreaming = on; mutate(K.aiStreaming, on) }
    fun setAiSendPageContext(on: Boolean) { aiSendPageContext = on; mutate(K.aiSendPageContext, on) }
    fun setAiWebAccess(on: Boolean) { aiWebAccess = on; mutate(K.aiWebAccess, on) }
    fun setAgentCode(v: String) { agentCode = v; mutate(K.agentCode, v) }
    fun setAgentServer(v: String) { agentServer = v; mutate(K.agentServer, v) }
    fun setAgentTimeoutSec(v: Int) { agentTimeoutSec = v.coerceIn(10, 600); mutate(K.agentTimeoutSec, agentTimeoutSec) }
    fun setAgentFallback(on: Boolean) { agentFallback = on; mutate(K.agentFallback, on) }
    fun setSwarmEnabled(on: Boolean) { swarmEnabled = on; mutate(K.swarmEnabled, on) }
    fun setSwarmMembers(v: Set<String>) { swarmMembers = v; mutate(K.swarmMembers, v) }
    fun setSwarmMode(v: Int) { swarmMode = v.coerceIn(0, 1); mutate(K.swarmMode, swarmMode) }
    fun setAiToolsPage(on: Boolean) { aiToolsPage = on; mutate(K.aiToolsPage, on) }
    fun setAiToolsBrowser(on: Boolean) { aiToolsBrowser = on; mutate(K.aiToolsBrowser, on) }
    fun setAiToolsData(on: Boolean) { aiToolsData = on; mutate(K.aiToolsData, on) }
    fun setAiTrustActions(on: Boolean) { aiTrustActions = on; mutate(K.aiTrustActions, on) }
    fun setAiToolsModels(on: Boolean) { aiToolsModels = on; mutate(K.aiToolsModels, on) }
    fun setAiToolsWrite(on: Boolean) { aiToolsWrite = on; mutate(K.aiToolsWrite, on) }
    fun setAiWriteAnywhere(on: Boolean) { aiWriteAnywhere = on; mutate(K.aiWriteAnywhere, on) }
    fun setAiToolsTasks(on: Boolean) { aiToolsTasks = on; mutate(K.aiToolsTasks, on) }
    fun setBurnTargets(v: Set<String>) { burnTargets = v; mutate(K.burnTargets, v) }
    fun setBurnConfirm(on: Boolean) { burnConfirm = on; mutate(K.burnConfirm, on) }
    fun setSttEndpoint(v: String) { sttEndpoint = v; mutate(K.sttEndpoint, v) }
    fun setSttModel(v: String) { sttModel = v; mutate(K.sttModel, v) }
    fun setSttKey(v: String) { sttKey = v; mutate(K.sttKey, v) }
    fun setVoiceEngine(v: String) { voiceEngine = v; mutate(K.voiceEngine, v) }
    fun setVoiceLang(v: String) { voiceLang = v; mutate(K.voiceLang, v) }
    fun setAiReplyNotify(on: Boolean) { aiReplyNotify = on; mutate(K.aiReplyNotify, on) }
    fun setFaviconNetwork(on: Boolean) { faviconNetwork = on; mutate(K.faviconNetwork, on) }
    fun setAiMemory(v: String) { aiMemory = v; mutate(K.aiMemory, v) }
    fun setAiMemoryOn(on: Boolean) { aiMemoryOn = on; mutate(K.aiMemoryOn, on) }
    fun setAiToolsScripts(on: Boolean) { aiToolsScripts = on; mutate(K.aiToolsScripts, on) }
    fun setAiWorkflows(v: String) { aiWorkflows = v; mutate(K.aiWorkflows, v) }
    fun setAiBusiness(v: String) { aiBusiness = v; mutate(K.aiBusiness, v) }
    fun setAiDocNumbers(v: String) { aiDocNumbers = v; mutate(K.aiDocNumbers, v) }
    fun setAiTemplateGroups(v: String) { aiTemplateGroups = v; mutate(K.aiTemplateGroups, v) }
    fun setTasksSpan(v: Int) { tasksSpan = if (v in intArrayOf(1, 3, 6, 12)) v else 1; mutate(K.tasksSpan, tasksSpan) }
    fun setAiNoThinking(v: Set<String>) { aiNoThinking = v; mutate(K.aiNoThinking, v) }

    /** v1.7.25: persist the saved AI model profiles (JSON array) and the active index. */
    fun setAiProfiles(json: String, active: Int) {
        aiProfiles = json; aiActiveProfile = active
        edit {
            it[K.aiProfiles] = json
            it[K.aiActiveProfile] = active
        }
    }
    fun setHomeTileSize(size: Int) { homeTileSize = size; mutate(K.homeTileSize, size) }
    fun setHomeGridColumns(cols: Int) { homeGridColumns = cols; mutate(K.homeGridColumns, cols) }
    fun setPayAutofill(on: Boolean) { payAutofill = on; mutate(K.payAutofill, on) }

    /** All app themes in gallery order (id → style res). 24 themes (v1.7.24: +6). */
    fun themeStyle(): Int = when (themeSnapshot(appContext)) {
        1 -> R.style.Theme_Zephyr_Dark
        2 -> R.style.Theme_Zephyr_Amoled
        3 -> R.style.Theme_Zephyr_Sepia
        4 -> R.style.Theme_Zephyr_Slate
        5 -> R.style.Theme_Zephyr_Rose
        6 -> R.style.Theme_Zephyr_Forest
        7 -> R.style.Theme_Zephyr_Ocean
        8 -> R.style.Theme_Zephyr_Plum
        9 -> R.style.Theme_Zephyr_Sand
        10 -> R.style.Theme_Zephyr_Midnight
        11 -> R.style.Theme_Zephyr_Porcelain
        12 -> R.style.Theme_Zephyr_Aurora
        13 -> R.style.Theme_Zephyr_Crimson
        14 -> R.style.Theme_Zephyr_Mono
        15 -> R.style.Theme_Zephyr_Mint
        16 -> R.style.Theme_Zephyr_Sunset
        17 -> R.style.Theme_Zephyr_VioletNight
        18 -> R.style.Theme_Zephyr_Cyberpunk
        19 -> R.style.Theme_Zephyr_Sakura
        20 -> R.style.Theme_Zephyr_Espresso
        21 -> R.style.Theme_Zephyr_Nord
        22 -> R.style.Theme_Zephyr_Emerald
        23 -> R.style.Theme_Zephyr_Amber
        else -> R.style.Theme_Zephyr
    }

    /** Daily blocked counter (called from flush on UI thread). */
    fun addBlockedToday(n: Long) {
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val todayCount = if (today != blockedDay) 0 else blockedToday
        blockedDay = today; blockedToday = todayCount + n; blockedTotal += n
        edit {
            it[K.blockedDay] = today
            it[K.blockedToday] = blockedToday
            it[K.blockedTotal] = blockedTotal
        }
    }


    // ---------------- v1.7.6 setters ----------------

    fun setPullToRefresh(on: Boolean) { pullToRefresh = on; mutate(K.pullToRefresh, on) }
    fun setOmniboxSwipeRefresh(on: Boolean) { omniboxSwipeRefresh = on; mutate(K.omniboxSwipeRefresh, on) }
    fun setDoubleTapScroll(on: Boolean) { doubleTapScroll = on; mutate(K.doubleTapScroll, on) }
    fun setVideoSniffer(on: Boolean) { videoSniffer = on; mutate(K.videoSniffer, on) }
    // v1.7.8
    fun setDownloadDirUri(uri: String) { downloadDirUri = uri; mutate(K.downloadDirUri, uri) }
    fun setFilesGrid(grid: Boolean) { filesGrid = grid; mutate(K.filesGrid, grid) }
    fun setFilesZoom(zoom: Int) { filesZoom = zoom.coerceIn(0, 3); mutate(K.filesZoom, filesZoom) }
    fun setHomeFeedMode(mode: Int) { homeFeedMode = mode; mutate(K.homeFeedMode, mode) }
    fun setDmWifiOnly(on: Boolean) { dmWifiOnly = on; mutate(K.dmWifiOnly, on) }
    fun setDmNightOnly(on: Boolean) { dmNightOnly = on; mutate(K.dmNightOnly, on) }
    fun setPaymentsGate(on: Boolean) { paymentsGate = on; mutate(K.paymentsGate, on) }

    // v1.7.14: parses "2,0,1" → [2,0,1]; anything broken falls back to [0,1,2]
    fun filesTabOrderList(): List<Int> {
        val parsed = filesTabOrder.split(',').mapNotNull { it.trim().toIntOrNull() }
        val valid = parsed.filter { it in 0..2 }.distinct()
        return if (valid.size == 3) valid else listOf(0, 1, 2)
    }
    fun setFilesTabOrder(order: List<Int>) {
        val v = order.filter { it in 0..2 }.distinct()
        filesTabOrder = if (v.size == 3) v.joinToString(",") else "0,1,2"
        mutate(K.filesTabOrder, filesTabOrder)
    }
    fun setHomeVariant(v: Int) { homeVariant = v.coerceIn(0, 4); mutate(K.homeVariant, homeVariant) }
    fun setHomeCustomUrl(url: String) { homeCustomUrl = url.trim(); mutate(K.homeCustomUrl, homeCustomUrl) }
    fun setFilesSort(sort: Int) { filesSort = sort; mutate(K.filesSort, sort) }
    fun isHintDismissed(key: String): Boolean = key in dismissedHints
    fun dismissHint(key: String) {
        dismissedHints = dismissedHints + key
        mutate(K.dismissedHints, dismissedHints)
    }
    fun setNightSchedule(on: Boolean) { nightSchedule = on; mutate(K.nightSchedule, on) }
    fun setNightWindow(startMin: Int, endMin: Int) {
        nightStart = startMin; nightEnd = endMin
        edit { it[K.nightStart] = startMin; it[K.nightEnd] = endMin }
    }

    /** v1.7.6: engaged/disengaged by the night-schedule engine (MainActivity ticker). */
    fun setNightActive(active: Boolean, savedTheme: Int, savedAppearance: Int) {
        nightActive = active; nightSavedTheme = savedTheme; nightSavedAppearance = savedAppearance
        edit {
            it[K.nightActive] = active
            it[K.nightSavedTheme] = savedTheme
            it[K.nightSavedAppearance] = savedAppearance
        }
    }

    fun toggleBackupInclude(id: String, on: Boolean) {
        backupInclude = if (on) backupInclude + id else backupInclude - id
        mutate(K.backupInclude, backupInclude)
    }

    fun setAutoBackup(on: Boolean) { autoBackup = on; mutate(K.autoBackup, on) }
    fun setLastBackupAt(at: Long) { lastBackupAt = at; mutate(K.lastBackupAt, at) }

    /** v1.7.6: is "now" inside the configured night window (handles windows over midnight). */
    fun isNightWindowNow(): Boolean {
        if (!nightSchedule) return false
        val cal = java.util.Calendar.getInstance()
        val now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return if (nightStart <= nightEnd) now in nightStart..nightEnd
        else now >= nightStart || now < nightEnd
    }

    fun currentEngine(): SearchEngines.Engine {
        return if (engineId == "custom" && customEngineUrl.isNotEmpty()) SearchEngines.Engine(
            "custom", R.string.engine_custom, customEngineUrl, "https://lens.google.com/", "https://duck.ai/?origin=funnel_home_searchresults"
        ) else SearchEngines.byId(engineId)
    }
}
