package com.nuvio.remote.update

import com.nuvio.remote.ui.i18n.ResolvedLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun `semantic version comparison handles major minor patch and prerelease`() {
        assertTrue(isNewerVersion("0.4.3", "0.5.0"))
        assertTrue(isNewerVersion("v0.4.3", "v0.10.0"))
        assertTrue(isNewerVersion("0.5.0-rc1", "0.5.0"))
        assertTrue(isNewerVersion("0.5.0-rc.2", "0.5.0-rc.10"))
        assertFalse(isNewerVersion("0.5.0-rc.10", "0.5.0-rc.2"))
        assertFalse(isNewerVersion("0.5.0", "0.5.0"))
        assertFalse(isNewerVersion("0.5.1", "0.5.0"))
        assertFalse(isNewerVersion("0.5.0", "0.5.0-beta.1"))
    }

    @Test
    fun `download url allowlist enforces official repo path and GitHub release CDNs`() {
        val validGh = "https://github.com/Offrain-code/nuvio-deskremote/releases/download/v0.5.0/app.apk"
        val httpGh = "http://github.com/Offrain-code/nuvio-deskremote/releases/download/v0.5.0/app.apk"
        assertTrue(isAllowedDownloadUrl(validGh))
        assertTrue(isAllowedDownloadUrl("https://objects.githubusercontent.com/github-production-release-asset/123"))
        assertTrue(
            isAllowedDownloadUrl("https://release-assets.githubusercontent.com/github-production-release-asset/123"),
        )
        assertFalse(isAllowedDownloadUrl("https://github.com/attacker/evil-repo/releases/download/v1.0.0/app.apk"))
        assertFalse(
            isAllowedDownloadUrl("https://github.com/Offrain-code/nuvio-deskremote/releases/download/../evil.apk"),
        )
        assertFalse(isAllowedDownloadUrl(httpGh))
        assertFalse(isAllowedDownloadUrl("https://evil.example.com/nuvio.apk"))
        assertFalse(isAllowedDownloadUrl("https://github.com.evil.com/nuvio.apk"))
    }

    @Test
    fun `parseSha256Sums matches release asset digest case-insensitively`() {
        val sums = """
            # SHA-256 checksums
            0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  *nuvio-deskremote-v0.5.1.apk
        """.trimIndent()
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            parseSha256Sums(sums, "Nuvio-DeskRemote-v0.5.1.apk"),
        )
        assertNull(parseSha256Sums(sums, "other.apk"))
    }

    @Test
    fun `github release json parser extracts apk digest notes asset and ignores drafts`() {
        val sampleJson = """
            {
              "tag_name": "v0.5.0",
              "draft": false,
              "prerelease": false,
              "html_url": "https://github.com/Offrain-code/nuvio-deskremote/releases/tag/v0.5.0",
              "body": "## What's Changed\n- Built-in auto-update popup by @Offrain-code in https://github.com/Offrain-code/nuvio-deskremote/pull/10\n- Double-tap-and-hold drag on Screen Share\n\n**Full Changelog**: https://github.com/Offrain-code/nuvio-deskremote/compare/v0.4.3...v0.5.0",
              "assets": [
                {
                  "name": "nuvio-deskremote-v0.5.0-release.apk",
                  "size": 14889779,
                  "digest": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "browser_download_url": "https://github.com/Offrain-code/nuvio-deskremote/releases/download/v0.5.0/nuvio-deskremote-v0.5.0-release.apk"
                },
                {
                  "name": "release-notes.json",
                  "size": 512,
                  "browser_download_url": "https://github.com/Offrain-code/nuvio-deskremote/releases/download/v0.5.0/release-notes.json"
                }
              ]
            }
        """.trimIndent()

        val release = parseGitHubReleaseJson(sampleJson, currentVersion = "0.4.3")
        assertNotNull(release)
        assertEquals("0.5.0", release!!.version)
        assertEquals("nuvio-deskremote-v0.5.0-release.apk", release.apkName)
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            release.expectedSha256,
        )
        assertNotNull(release.notesAssetUrl)
        assertEquals(
            listOf(
                "Built-in auto-update popup",
                "Double-tap-and-hold drag on Screen Share",
            ),
            release.notesEn,
        )
        assertNull(parseGitHubReleaseJson(sampleJson, currentVersion = "0.5.0"))
    }

    @Test
    fun `localized release notes switch dynamically between english and pt-br`() {
        val notesJson = """
            {
              "en": ["Auto-update popup", "Double-tap drag"],
              "pt-BR": ["Popup de atualização automática", "Arrastar com 2 toques"]
            }
        """.trimIndent()
        val (en, pt) = parseLocalizedReleaseNotesJson(notesJson)
        val release = AppUpdateRelease(
            version = "0.5.0",
            tagName = "v0.5.0",
            apkName = "app.apk",
            apkDownloadUrl = "https://github.com/Offrain-code/nuvio-deskremote/releases/download/v0.5.0/app.apk",
            apkSizeBytes = 1024L,
            expectedSha256 = null,
            notesEn = en,
            notesPtBr = pt,
            htmlUrl = "https://github.com/Offrain-code/nuvio-deskremote/releases/tag/v0.5.0",
        )

        assertEquals(listOf("Auto-update popup", "Double-tap drag"), release.notesFor(ResolvedLanguage.EN))
        assertEquals(
            listOf("Popup de atualização automática", "Arrastar com 2 toques"),
            release.notesFor(ResolvedLanguage.PT_BR),
        )
    }

    @Test
    fun `later dismissal snoozes same version for 24h but allows newer version`() {
        val t0 = 1_000_000L
        assertFalse(shouldShowAutoPrompt("0.5.0", "0.5.0", t0, t0 + 3_600_000L))
        assertTrue(shouldShowAutoPrompt("0.5.0", "0.5.0", t0, t0 + UPDATE_DISMISS_COOLDOWN_MS + 1L))
        assertTrue(shouldShowAutoPrompt("0.5.1", "0.5.0", t0, t0 + 1_000L))
    }
}
