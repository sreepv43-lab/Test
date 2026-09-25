package io.github.sreepv43.streamhub.ui

import android.net.Uri

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val DOWNLOADS = "downloads"
    const val ADDONS = "addons?install={install}"
    const val SETTINGS = "settings"
    const val LINK = "link?url={url}"
    const val DETAIL = "detail/{type}/{id}"
    const val STREAMS = "streams/{type}/{metaId}/{videoId}"
    const val CATALOG = "catalog?addon={addon}&type={type}&id={id}"

    fun addons(install: String? = null) = if (install == null) "addons" else "addons?install=${Uri.encode(install)}"
    fun link(url: String? = null) = if (url == null) "link" else "link?url=${Uri.encode(url)}"
    fun detail(type: String, id: String) = "detail/${Uri.encode(type)}/${Uri.encode(id)}"
    fun streams(type: String, metaId: String, videoId: String) =
        "streams/${Uri.encode(type)}/${Uri.encode(metaId)}/${Uri.encode(videoId)}"
    fun catalog(addon: String, type: String, id: String) =
        "catalog?addon=${Uri.encode(addon)}&type=${Uri.encode(type)}&id=${Uri.encode(id)}"
}
