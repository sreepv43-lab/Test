package io.github.sreepv43.streamhub.addon

/** A well-known addon offered for one-click install on the Addons page. */
data class SuggestedAddon(val name: String, val description: String, val url: String)

/** Popular addons for catalogs, metadata, subtitles and legal sources. */
object AddonCatalog {
    const val YOUTUBE = "https://v3-channels.strem.io/manifest.json"

    val suggested = listOf(
        SuggestedAddon("Cinemeta", "Official catalogs and details for movies and series", "https://v3-cinemeta.strem.io/manifest.json"),
        SuggestedAddon("OpenSubtitles v3", "Subtitles in many languages", "https://opensubtitles-v3.strem.io/manifest.json"),
        SuggestedAddon("YouTube", "YouTube channels and videos (opens in the YouTube app)", YOUTUBE),
        SuggestedAddon("WatchHub", "Shows which streaming services carry a title", "https://watchhub.strem.io/manifest.json"),
        SuggestedAddon(
            "Streaming Catalogs",
            "Catalogs of Netflix, Prime Video, Disney+ and other services",
            "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/manifest.json",
        ),
        SuggestedAddon(
            "The Movie Database",
            "Catalogs and details from TMDB, in many languages",
            "https://94c8cb9f702d-tmdb-addon.baby-beamup.club/manifest.json",
        ),
        SuggestedAddon("Anime Kitsu", "Anime catalogs and details from Kitsu", "https://anime-kitsu.strem.fun/manifest.json"),
    )
}
