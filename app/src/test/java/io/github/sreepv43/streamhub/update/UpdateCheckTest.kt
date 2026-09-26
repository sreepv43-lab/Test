package io.github.sreepv43.streamhub.update

import io.github.sreepv43.streamhub.addon.StremioJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckTest {
    private val release = StremioJson.decodeFromString(
        GitHubRelease.serializer(),
        """
        {"tag_name": "build-31", "name": "StreamHub build 31", "assets": [
          {"name": "app-arm64-v8a-release.apk", "browser_download_url": "https://x/arm64.apk", "size": 19000000},
          {"name": "app-armeabi-v7a-release.apk", "browser_download_url": "https://x/arm.apk", "size": 17000000},
          {"name": "app-universal-release.apk", "browser_download_url": "https://x/all.apk", "size": 65000000}
        ]}
        """,
    )

    @Test
    fun picksTheApkForTheDevicesFirstCpuType() {
        val update = UpdateCheck.pick(release, listOf("armeabi-v7a", "armeabi"), currentBuild = 24)!!
        assertEquals(31, update.build)
        assertEquals("https://x/arm.apk", update.apkUrl)
        assertEquals(17000000L, update.size)
    }

    @Test
    fun fallsBackToTheUniversalApk() {
        assertEquals("https://x/all.apk", UpdateCheck.pick(release, listOf("mips"), currentBuild = 24)!!.apkUrl)
    }

    @Test
    fun nothingWhenAlreadyUpToDate() {
        assertNull(UpdateCheck.pick(release, listOf("arm64-v8a"), currentBuild = 31))
        assertNull(UpdateCheck.pick(release, listOf("arm64-v8a"), currentBuild = 40))
    }

    @Test
    fun buildNumberFromTag() {
        assertEquals(7, UpdateCheck.buildNumber("build-7"))
        assertNull(UpdateCheck.buildNumber("latest"))
    }
}
