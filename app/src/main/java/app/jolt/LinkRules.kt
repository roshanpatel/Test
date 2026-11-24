package app.jolt

import java.net.URI

// Rules for Jolt's link viewer. Pure Kotlin, no Android imports.

/** How long one link may stay open in the viewer. */
const val LINK_VIEW_MS = 10 * MINUTE_MS

/** Feeds, social sites and gambling: never opened in the viewer, so a link can't become a way in. */
private val REFUSED_SITES = setOf(
    // Social and feeds
    "facebook.com", "fb.com", "fb.watch", "messenger.com", "instagram.com", "threads.net",
    "reddit.com", "redd.it", "tiktok.com", "youtube.com", "youtu.be", "twitter.com", "x.com",
    "snapchat.com", "pinterest.com", "tumblr.com", "twitch.tv", "9gag.com", "imgur.com",
    "linkedin.com", "kick.com", "rumble.com", "dailymotion.com", "vimeo.com",
    // Search engines: searching would turn the viewer into a browser
    "bing.com", "duckduckgo.com", "yahoo.com", "yandex.com", "ecosia.org", "startpage.com",
    "search.brave.com", "baidu.com", "ask.com",
    // Gambling
    "bet365.com", "williamhill.com", "paddypower.com", "skybet.com", "betfair.com",
    "ladbrokes.com", "coral.co.uk", "betway.com", "888.com", "pokerstars.com", "betvictor.com",
    "unibet.com", "betfred.com", "boylesports.com", "draftkings.com", "fanduel.com", "stake.com",
)

/** Second-level parts that belong to the country, e.g. the "co" in bbc.co.uk. */
private val COUNTRY_SECOND_LEVELS = setOf("co", "com", "org", "net", "ac", "gov", "ltd", "plc", "edu", "nhs", "me", "sch")

fun hostOf(url: String): String? = try {
    URI(url).host?.lowercase()?.removePrefix("www.")
} catch (e: Exception) {
    null
}

/** The registrable part of a host: "news.bbc.co.uk" → "bbc.co.uk", "m.facebook.com" → "facebook.com". */
fun siteOf(host: String): String {
    val labels = host.lowercase().removePrefix("www.").split('.').filter { it.isNotEmpty() }
    if (labels.size <= 2) return labels.joinToString(".")
    val keep = if (labels[labels.size - 2] in COUNTRY_SECOND_LEVELS && labels.last().length == 2) 3 else 2
    return labels.takeLast(keep).joinToString(".")
}

fun sameSite(a: String, b: String) = siteOf(a) == siteOf(b)

private fun isGoogle(host: String) = siteOf(host).let { it.startsWith("google.") }

/** Why a page can't be opened in the viewer, or null if it can. */
fun refusalFor(url: String): String? {
    val host = hostOf(url) ?: return null
    val site = siteOf(host)
    if (site in REFUSED_SITES || host in REFUSED_SITES) {
        return "$site isn't opened here. Feeds, search and betting sites stay closed."
    }
    if (isGoogle(host)) {
        val path = try {
            URI(url).path.orEmpty()
        } catch (e: Exception) {
            ""
        }
        if (path.isEmpty() || path == "/" || path.startsWith("/search") || path.startsWith("/webhp")) {
            return "Search isn't available here. Links only."
        }
    }
    return null
}

fun isWebLink(url: String) = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
