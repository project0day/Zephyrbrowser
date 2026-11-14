package com.zephyr.browser

/** Search engines with per-engine ecosystem URLs (search / image search / AI chat). */
object SearchEngines {

    data class Engine(
        val id: String,
        val nameRes: Int,
        val urlTemplate: String,      // %s = encoded query
        val imageSearchUrl: String,   // page to open for "search by image"
        val aiChatUrl: String,        // engine-consistent AI assistant
        val suggestTemplate: String? = null
    )

    val google = Engine("google", R.string.engine_google,
        "https://www.google.com/search?q=%s",
        "https://lens.google.com/",
        "https://gemini.google.com/app",
        "https://suggestqueries.google.com/complete/search?client=firefox&q=%s")

    val yandex = Engine("yandex", R.string.engine_yandex,
        "https://yandex.ru/search/?text=%s",
        "https://yandex.ru/images/",
        "https://alice.yandex.ru/",
        "https://suggest.yandex.ru/suggest/ff?part=%s")

    val duckduckgo = Engine("duckduckgo", R.string.engine_duckduckgo,
        "https://duckduckgo.com/?q=%s",
        "https://duckduckgo.com/?q=&iax=images&ia=images",
        "https://duck.ai/?origin=funnel_home_searchresults")

    val bing = Engine("bing", R.string.engine_bing,
        "https://www.bing.com/search?q=%s",
        "https://www.bing.com/images?q=",
        "https://copilot.microsoft.com/",
        "https://api.bing.com/osjson.aspx?query=%s")

    val brave = Engine("brave", R.string.engine_brave,
        "https://search.brave.com/search?q=%s",
        "https://search.brave.com/images?q=",
        "https://search.brave.com/chat")

    val startpage = Engine("startpage", R.string.engine_startpage,
        "https://www.startpage.com/sp/search?query=%s",
        "https://www.startpage.com/isp/image-search?query=",
        "https://duck.ai/?origin=funnel_home_searchresults")

    val ecosia = Engine("ecosia", R.string.engine_ecosia,
        "https://www.ecosia.org/search?q=%s",
        "https://www.ecosia.org/images?q=",
        "https://www.ecosia.org/chat")

    val builtIn = listOf(google, yandex, duckduckgo, bing, brave, startpage, ecosia)

    fun byId(id: String?): Engine = builtIn.firstOrNull { it.id == id } ?: google

    fun searchUrl(engineId: String, query: String): String =
        byId(engineId).urlTemplate.replace("%s", java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20"))

    fun searchUrlTemplate(engineId: String): (String) -> String = { q -> searchUrl(engineId, q) }
}
