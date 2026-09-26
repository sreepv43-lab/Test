package io.github.sreepv43.streamhub.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The parts of GitHub's "latest release" response the updater needs. */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tag: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
)

data class AvailableUpdate(val build: Int, val apkName: String, val apkUrl: String, val size: Long)

object UpdateCheck {
    /** Releases are tagged `build-<number>`, the same number the app is built with. */
    fun buildNumber(tag: String): Int? = Regex("""(\d+)$""").find(tag)?.value?.toIntOrNull()

    /**
     * The update to install, or null if [release] isn't newer than [currentBuild]: the APK for the
     * first CPU type in [abis] (the device's, best first), else the universal one.
     */
    fun pick(release: GitHubRelease, abis: List<String>, currentBuild: Int): AvailableUpdate? {
        val build = buildNumber(release.tag) ?: return null
        if (build <= currentBuild) return null
        val asset = abis.firstNotNullOfOrNull { abi -> release.assets.firstOrNull { it.name == "app-$abi-release.apk" } }
            ?: release.assets.firstOrNull { it.name == "app-universal-release.apk" }
            ?: return null
        return AvailableUpdate(build, asset.name, asset.url, asset.size)
    }
}
