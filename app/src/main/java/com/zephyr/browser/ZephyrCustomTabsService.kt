package com.zephyr.browser

import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsSessionToken

/**
 * v1.8.0 (user: «в твиттере ссылка открывается во внутреннем браузере, но
 * открывается Kiwi, хотя браузер по умолчанию Zephyr»).
 *
 * Exactly so, and being the default browser had nothing to do with it. When an
 * app opens a link "inside itself" it is using **Custom Tabs**, and Android
 * offers only those browsers that declare a `CustomTabsService`. Zephyr
 * declared none, so it was not a candidate at all — Android picked a browser
 * that was, and Kiwi happened to be installed.
 *
 * Declaring this service makes Zephyr eligible. The tab itself still arrives as
 * an ordinary `ACTION_VIEW` intent and opens as a normal Zephyr tab, which is
 * the honest behaviour for a browser that does not render a stripped-down chrome
 * of its own: the user gets their own browser, with their own blocker, scripts
 * and history, instead of a third-party window.
 *
 * The optimisation hooks — warm-up, pre-connect, speculative loading — are left
 * unimplemented on purpose. Each is a promise to spend the user's battery and
 * data on a page they may never open, and a browser that quietly prefetches on
 * another app's say-so is not the browser this one is trying to be. Returning
 * false to those calls is a valid answer; the caller simply does without.
 */
class ZephyrCustomTabsService : CustomTabsService() {

    override fun warmup(flags: Long): Boolean = false

    override fun newSession(sessionToken: CustomTabsSessionToken): Boolean = true

    /**
     * No speculative loading. See the class note: this would fetch a page the
     * user has not asked for, on a hint from another app.
     */
    override fun mayLaunchUrl(
        sessionToken: CustomTabsSessionToken,
        url: Uri?,
        extras: Bundle?,
        otherLikelyBundles: MutableList<Bundle>?
    ): Boolean = false

    override fun extraCommand(commandName: String, args: Bundle?): Bundle? = null

    override fun updateVisuals(sessionToken: CustomTabsSessionToken, bundle: Bundle?): Boolean = false

    override fun requestPostMessageChannel(
        sessionToken: CustomTabsSessionToken,
        postMessageOrigin: Uri
    ): Boolean = false

    override fun postMessage(
        sessionToken: CustomTabsSessionToken,
        message: String,
        extras: Bundle?
    ): Int = RESULT_FAILURE_DISALLOWED

    override fun validateRelationship(
        sessionToken: CustomTabsSessionToken,
        relation: Int,
        origin: Uri,
        extras: Bundle?
    ): Boolean = false

    override fun receiveFile(
        sessionToken: CustomTabsSessionToken,
        uri: Uri,
        purpose: Int,
        extras: Bundle?
    ): Boolean = false
}
