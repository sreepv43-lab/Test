package io.github.sreepv43.soundhub.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesTest {
    private val updates = Updates("owner/repo")

    private fun release(tag: String, vararg assets: String, draft: Boolean = false, body: String = "") = """
        {"tag_name": "$tag", "name": "${tag.replace('-', ' ')}", "draft": $draft, "prerelease": false,
         "body": "${body.replace("\n", "\\n")}",
         "assets": [${assets.joinToString { """{"name": "$it", "size": 1234, "browser_download_url": "https://example.test/$tag/$it"}""" }}]}
    """.trimIndent()

    private val releases = "[" + listOf(
        release("build-70", "app-universal-debug.apk"),
        release("soundhub-build-68", "soundhub-debug.apk", "soundhub-release.apk", body = "## What's new\nfix: faster search\n\n---\nBuilt from abc"),
        release("soundhub-build-69", "soundhub-debug.apk", "soundhub-release.apk", draft = true),
        release("soundhub-build-66", "soundhub-debug.apk", "soundhub-release.apk"),
    ).joinToString(",") + "]"

    @Test
    fun picksTheNewestPublishedSoundHubBuildWithTheSameKindOfApk() {
        val update = updates.newest(releases, currentBuild = 61, apkName = "soundhub-release.apk")!!
        assertEquals(68, update.build)
        assertEquals("https://example.test/soundhub-build-68/soundhub-release.apk", update.apkUrl)
        assertEquals("fix: faster search", update.notes)
        assertEquals(1234L, update.size)
    }

    @Test
    fun nothingWhenUpToDate() {
        assertNull(updates.newest(releases, currentBuild = 68, apkName = "soundhub-release.apk"))
        assertNull("StreamHub releases are ignored", updates.newest(releases, currentBuild = 68, apkName = "app-universal-debug.apk"))
    }

    @Test
    fun notesAreTheWhatsNewPart() {
        assertEquals("feat: artists\nfix: focus", Updates.notesOf("## What's new\nfeat: artists\nfix: focus\n\n---\nInstall ..."))
        assertEquals("", Updates.notesOf("Old release without notes"))
        assertEquals(
            "fix: sign in\n\nThe button can be reached.",
            Updates.notesOf("## What's new\nfix: sign in\n\nThe button can be reached.\n\nCo-Authored-By: A <a@b>\nClaude-Session: https://x\n\n---\nBuilt"),
        )
    }
}
