package com.eclipse.launcher.data

/**
 * Ad and tracker servers the walkthrough browser doesn't load. Guide sites (GameFAQs and the like) pull in dozens of
 * these per page; skipping them is most of what makes a page come up quickly on the bottom screen. A host matches
 * itself and every sub-domain ("pubads.g.doubleclick.net" → "doubleclick.net").
 */
object AdBlock {
    private val HOSTS = setOf(
        // Google ads and analytics
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.com", "imasdk.googleapis.com",
        // ad exchanges and header bidding
        "amazon-adsystem.com", "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com",
        "indexww.com", "criteo.com", "criteo.net", "3lift.com", "sharethrough.com", "teads.tv", "yieldmo.com",
        "adsrvr.org", "bidswitch.net", "smartadserver.com", "media.net", "lijit.com", "sovrn.com", "gumgum.com",
        "33across.com", "triplelift.com", "districtm.io", "contextweb.com", "spotxchange.com", "springserve.com",
        "tremorhub.com", "adform.net", "yieldlab.net", "adsafeprotected.com", "doubleverify.com", "moatads.com",
        "connatix.com", "primis.tech", "aniview.com", "vidazoo.com", "minutemedia-prebid.com", "seedtag.com",
        // recommendation widgets
        "taboola.com", "outbrain.com", "zergnet.com", "revcontent.com", "mgid.com",
        // trackers and audience data
        "scorecardresearch.com", "quantserve.com", "quantcount.com", "chartbeat.com", "chartbeat.net", "permutive.com",
        "permutive.app", "krxd.net", "bluekai.com", "demdex.net", "everesttech.net", "omtrdc.net", "hotjar.com",
        "segment.io", "segment.com", "nr-data.net", "crwdcntrl.net", "rlcdn.com", "tapad.com", "agkn.com",
        "bounceexchange.com", "facebook.net", "connect.facebook.net", "ads-twitter.com", "static.ads-twitter.com",
        "analytics.tiktok.com", "clarity.ms", "branch.io",
    )

    fun blocked(host: String?): Boolean {
        var h = host?.lowercase()?.trimEnd('.') ?: return false
        while (true) {
            if (h in HOSTS) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false // down to "example.com": no match
            h = h.substring(dot + 1)
        }
    }
}
