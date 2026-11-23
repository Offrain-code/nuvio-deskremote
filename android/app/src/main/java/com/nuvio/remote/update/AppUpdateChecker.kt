package com.nuvio.remote.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nuvio.remote.ui.i18n.ResolvedLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

const val GITHUB_LATEST_RELEASE_API_URL =
    "https://api.github.com/repos/Offrain-code/nuvio-deskremote/releases/latest"
const val UPDATE_DISMISS_COOLDOWN_MS = 24L * 60L * 60L * 1000L

private const val OFFICIAL_RELEASE_PATH_PREFIX = "/offrain-code/nuvio-deskremote/releases/download/"
private const val RELEASE_NOTES_ASSET_NAME = "release-notes.json"
private const val SHA256SUMS_ASSET_NAME = "SHA256SUMS.txt"
private const val MAX_NOTES_JSON_BYTES = 64 * 1024
private const val MAX_CHANGELOG_ITEMS = 8
private const val MAX_REDIRECT_HOPS = 5
private const val HTTP_REDIRECT_MOVED = 301
private const val HTTP_REDIRECT_FOUND = 302
private const val HTTP_REDIRECT_SEE_OTHER = 303
private const val HTTP_REDIRECT_TEMP = 307
private const val HTTP_REDIRECT_PERM = 308
private const val HTTPS_DEFAULT_PORT = 443
private const val PROGRESS_EMIT_INTERVAL_MS = 90L
private const val DOWNLOAD_BUFFER_BYTES = 32 * 1024
private const val BYTES_PER_KB = 1024.0
private const val BYTES_PER_MB = 1024.0 * 1024.0
private val ALLOWED_CDN_HOSTS = setOf(
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "github-releases.githubusercontent.com",
)

/** Parsed semantic version (`major.minor.patch[-preRelease]`) for safe release comparisons. */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        val majorCmp = major.compareTo(other.major)
        if (majorCmp != 0) return majorCmp
        val minorCmp = minor.compareTo(other.minor)
        if (minorCmp != 0) return minorCmp
        val patchCmp = patch.compareTo(other.patch)
        if (patchCmp != 0) return patchCmp
        return comparePreRelease(preRelease, other.preRelease)
    }
}

/** Parsed release metadata ready to render in the Liquid Glass AutoUpdateDialog. */
data class AppUpdateRelease(
    val version: String,
    val tagName: String,
    val apkName: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val expectedSha256: String?,
    val notesEn: List<String>,
    val notesPtBr: List<String>,
    val htmlUrl: String,
    val notesAssetUrl: String? = null,
    val checksumsAssetUrl: String? = null,
) {
    /** Returns release notes matching the active in-app display language with graceful fallback. */
    fun notesFor(lang: ResolvedLanguage): List<String> = when (lang) {
        ResolvedLanguage.PT_BR -> notesPtBr.ifEmpty { notesEn }
        ResolvedLanguage.EN -> notesEn.ifEmpty { notesPtBr }
    }
}

/** Reason why an update download or install step failed. */
enum class UpdateErrorReason {
    NETWORK,
    INTEGRITY,
    STORAGE,
    INSTALL_PERMISSION,
}

/** UI state machine for the AutoUpdateDialog modal. */
sealed class AppUpdateUiState {
    data object Hidden : AppUpdateUiState()

    data class Available(
        val currentVersion: String,
        val release: AppUpdateRelease,
    ) : AppUpdateUiState()

    data class Downloading(
        val currentVersion: String,
        val release: AppUpdateRelease,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : AppUpdateUiState()

    data class ReadyToInstall(
        val currentVersion: String,
        val release: AppUpdateRelease,
        val apkFile: File,
        val sha256Verified: Boolean,
    ) : AppUpdateUiState()

    data class Error(
        val currentVersion: String,
        val release: AppUpdateRelease?,
        val reason: UpdateErrorReason,
    ) : AppUpdateUiState()
}

/** Result returned after streaming and verifying a downloaded APK file. */
sealed class ApkDownloadOutcome {
    data class Success(val file: File, val sha256Verified: Boolean) : ApkDownloadOutcome()
    data class Failure(val reason: UpdateErrorReason) : ApkDownloadOutcome()
}

private val SEMVER_REGEX = Regex("""^[vV]?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+.*)?$""")
private val SHA256_REGEX = Regex("""^(?:sha256:)?([0-9a-fA-F]{64})$""")
private val PR_URL_SUFFIX_REGEX = Regex("""\s+by\s+@[\w-]+\s+in\s+https?://\S+$""")
private val MARKDOWN_LINK_REGEX = Regex("""\[([^]]+)]\(([^)]+)\)""")

private fun comparePreRelease(a: String?, b: String?): Int = when {
    a.isNullOrBlank() && b.isNullOrBlank() -> 0
    a.isNullOrBlank() -> 1
    b.isNullOrBlank() -> -1
    else -> comparePreReleaseIdentifiers(a.split('.'), b.split('.'))
}

private fun comparePreReleaseIdentifiers(aParts: List<String>, bParts: List<String>): Int {
    val limit = minOf(aParts.size, bParts.size)
    for (i in 0 until limit) {
        val pa = aParts[i]
        val pb = bParts[i]
        val na = pa.toLongOrNull()
        val nb = pb.toLongOrNull()
        val cmp = when {
            na != null && nb != null -> na.compareTo(nb)
            na != null -> -1
            nb != null -> 1
            else -> pa.compareTo(pb, ignoreCase = true)
        }
        if (cmp != 0) return cmp
    }
    return aParts.size.compareTo(bParts.size)
}

/** Parses a version string such as `"v0.5.0"` or `"0.4.3-rc1"`. */
fun parseSemanticVersion(raw: String): SemanticVersion? {
    val match = SEMVER_REGEX.matchEntire(raw.trim()) ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val minor = match.groupValues[2].toIntOrNull() ?: return null
    val patch = match.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
    val pre = match.groupValues[4].ifBlank { null }
    return SemanticVersion(major = major, minor = minor, patch = patch, preRelease = pre)
}

/** Returns `true` strictly when [candidate] is newer than [current]. */
fun isNewerVersion(current: String, candidate: String): Boolean {
    val cur = parseSemanticVersion(current) ?: return false
    val next = parseSemanticVersion(candidate) ?: return false
    return next > cur
}

/** Enforces HTTPS and official GitHub release asset paths/CDNs to prevent download hijacking. */
fun isAllowedDownloadUrl(url: String): Boolean {
    val parsed = url.trim().toHttpUrlOrNull() ?: return false
    if (!parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
    if (parsed.port != HTTPS_DEFAULT_PORT) return false
    if (parsed.pathSegments.any { it == ".." }) return false
    val host = parsed.host.lowercase(Locale.ROOT)
    if (host == "github.com") {
        return parsed.encodedPath.lowercase(Locale.ROOT).startsWith(OFFICIAL_RELEASE_PATH_PREFIX)
    }
    return host in ALLOWED_CDN_HOSTS
}

/** Parses a `SHA256SUMS.txt` file and returns the matching 64-hex SHA-256 digest for [assetName]. */
fun parseSha256Sums(text: String, assetName: String): String? {
    val target = assetName.trim()
    if (target.isEmpty()) return null
    return text.lineSequence().firstNotNullOfOrNull { parseSha256SumLine(it, target) }
}

private fun parseSha256SumLine(rawLine: String, target: String): String? {
    val line = rawLine.trim()
    val parts = if (line.isEmpty() || line.startsWith("#")) emptyList() else line.split(Regex("\\s+"), limit = 2)
    val hashMatch = parts.getOrNull(0)?.trim()?.let { SHA256_REGEX.matchEntire(it) } ?: return null
    val fileBase = parts.getOrNull(1)?.trim()?.removePrefix("*")
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?: return null
    return if (fileBase.equals(target, ignoreCase = true)) {
        hashMatch.groupValues[1].lowercase(Locale.ROOT)
    } else {
        null
    }
}

/** Decides whether the launch-time update popup should appear or stay snoozed after "Later". */
fun shouldShowAutoPrompt(latestVersion: String, dismissedVersion: String, dismissedAtMs: Long, nowMs: Long): Boolean {
    if (dismissedVersion.isBlank()) return true
    if (!dismissedVersion.equals(latestVersion, ignoreCase = true)) return true
    return (nowMs - dismissedAtMs) >= UPDATE_DISMISS_COOLDOWN_MS
}

/** Extracts clean bullet items from a GitHub Release Markdown body. */
fun parseMarkdownBulletLines(body: String): List<String> {
    if (body.isBlank()) return emptyList()
    return body.lineSequence()
        .mapNotNull(::cleanMarkdownBulletLine)
        .distinct()
        .take(MAX_CHANGELOG_ITEMS)
        .toList()
}

private fun cleanMarkdownBulletLine(rawLine: String): String? {
    val trimmed = rawLine.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("<!--")) return null
    if (trimmed.startsWith("**Full Changelog**", ignoreCase = true)) return null
    val stripped = when {
        trimmed.startsWith("- ") -> trimmed.drop(2)
        trimmed.startsWith("* ") -> trimmed.drop(2)
        trimmed.startsWith("• ") -> trimmed.drop(2)
        else -> return null
    }
    val noLinks = MARKDOWN_LINK_REGEX.replace(stripped, "$1")
    val noPrTail = PR_URL_SUFFIX_REGEX.replace(noLinks, "")
    val normalized = noPrTail
        .replace("**", "")
        .replace("`", "")
        .trim()
    return normalized.takeIf { it.isNotEmpty() }
}

/** Parses an optional `release-notes.json` release asset (`{"en": [...], "pt-BR": [...]}`). */
fun parseLocalizedReleaseNotesJson(jsonText: String): Pair<List<String>, List<String>> {
    val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList<String>() to emptyList()
    val en = readStringArray(obj.optJSONArray("en"))
    val pt = readStringArray(obj.optJSONArray("pt-BR") ?: obj.optJSONArray("pt_BR") ?: obj.optJSONArray("pt"))
    return en to pt
}

private fun readStringArray(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    val result = mutableListOf<String>()
    for (i in 0 until array.length()) {
        val text = array.optString(i, "").trim()
        if (text.isNotEmpty()) result.add(text)
        if (result.size >= MAX_CHANGELOG_ITEMS) break
    }
    return result
}

/** Parses GitHub `/releases/latest` JSON and returns an [AppUpdateRelease] if a newer valid APK exists. */
fun parseGitHubReleaseJson(jsonText: String, currentVersion: String): AppUpdateRelease? {
    val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
    val tagName = root.optString("tag_name", "").trim()
    val cleanVersion = tagName.removePrefix("v").removePrefix("V")
    val isEligible = !root.optBoolean("draft", false) &&
        !root.optBoolean("prerelease", false) &&
        cleanVersion.isNotEmpty() &&
        isNewerVersion(currentVersion, cleanVersion)
    if (!isEligible) return null

    val assets = root.optJSONArray("assets") ?: return null
    val apkAsset = findApkAsset(assets) ?: return null
    return buildUpdateRelease(root, assets, apkAsset, tagName, cleanVersion)
}

private fun buildUpdateRelease(
    root: JSONObject,
    assets: JSONArray,
    apkAsset: JSONObject,
    tagName: String,
    cleanVersion: String,
): AppUpdateRelease? {
    val apkUrl = apkAsset.optString("browser_download_url", "").trim()
    if (!isAllowedDownloadUrl(apkUrl)) return null
    val notesAssetUrl = findAssetUrlByName(assets, RELEASE_NOTES_ASSET_NAME)
        ?.takeIf(::isAllowedDownloadUrl)
    val checksumsAssetUrl = findAssetUrlByName(assets, SHA256SUMS_ASSET_NAME)
        ?.takeIf(::isAllowedDownloadUrl)
    val digestRaw = apkAsset.optString("digest", "").trim()
    val expectedSha256 = SHA256_REGEX.matchEntire(digestRaw)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    val fallbackNotes = parseMarkdownBulletLines(root.optString("body", ""))
    return AppUpdateRelease(
        version = cleanVersion,
        tagName = tagName,
        apkName = apkAsset.optString("name", "nuvio-deskremote-$cleanVersion.apk"),
        apkDownloadUrl = apkUrl,
        apkSizeBytes = apkAsset.optLong("size", 0L).coerceAtLeast(0L),
        expectedSha256 = expectedSha256,
        notesEn = fallbackNotes,
        notesPtBr = emptyList(),
        htmlUrl = root.optString("html_url", "").trim(),
        notesAssetUrl = notesAssetUrl,
        checksumsAssetUrl = checksumsAssetUrl,
    )
}

private fun findApkAsset(assets: JSONArray): JSONObject? {
    val apkAssets = (0 until assets.length())
        .mapNotNull { assets.optJSONObject(it) }
        .filter { it.optString("name", "").lowercase(Locale.ROOT).endsWith(".apk") }
    return apkAssets.firstOrNull {
        it.optString("name", "").lowercase(Locale.ROOT).contains("release")
    } ?: apkAssets.firstOrNull()
}

private fun findAssetUrlByName(assets: JSONArray, expectedName: String): String? {
    for (i in 0 until assets.length()) {
        val asset = assets.optJSONObject(i) ?: continue
        val name = asset.optString("name", "")
        if (name.equals(expectedName, ignoreCase = true)) {
            return asset.optString("browser_download_url", "").trim().ifEmpty { null }
        }
    }
    return null
}

/** Formats byte counts using locale-appropriate decimal separator (`14.2 MB` vs `14,2 MB`). */
fun formatFileSize(bytes: Long, lang: ResolvedLanguage): String {
    val safe = bytes.coerceAtLeast(0L)
    val locale = if (lang == ResolvedLanguage.PT_BR) Locale.forLanguageTag("pt-BR") else Locale.US
    return if (safe >= BYTES_PER_MB) {
        String.format(locale, "%.1f MB", safe / BYTES_PER_MB)
    } else {
        String.format(locale, "%.0f KB", safe / BYTES_PER_KB)
    }
}

/** Reads the installed app version name safely across Android API levels. */
fun resolveInstalledVersionName(context: Context): String = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    info.versionName?.trim().orEmpty().ifEmpty { "0.0.0" }
}.getOrDefault("0.0.0")

/** Handles network check, streaming APK download with SHA-256 verification, and FileProvider install. */
object AppUpdateService {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Queries GitHub Releases for the latest official release and optional bilingual notes asset. */
    suspend fun checkLatestRelease(currentVersion: String): Result<AppUpdateRelease?> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(GITHUB_LATEST_RELEASE_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "NuvioDeskRemote-Android/$currentVersion")
                .build()
            val bodyText = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("GitHub API returned HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
            val parsed = parseGitHubReleaseJson(bodyText, currentVersion) ?: return@runCatching null
            val withSha = enrichWithChecksums(parsed, currentVersion)
            enrichWithLocalizedNotes(withSha, currentVersion)
        }
    }

    private fun executeWithValidatedRedirects(initialRequest: Request): okhttp3.Response {
        var currentReq = initialRequest
        repeat(MAX_REDIRECT_HOPS + 1) { hop ->
            val resp = httpClient.newCall(currentReq).execute()
            val isRedirect = resp.code == HTTP_REDIRECT_MOVED ||
                resp.code == HTTP_REDIRECT_FOUND ||
                resp.code == HTTP_REDIRECT_SEE_OTHER ||
                resp.code == HTTP_REDIRECT_TEMP ||
                resp.code == HTTP_REDIRECT_PERM
            if (!isRedirect) return resp
            val location = resp.header("Location")?.trim()
            val resolved = location?.let { currentReq.url.resolve(it)?.toString() }
            resp.close()
            if (hop >= MAX_REDIRECT_HOPS || resolved == null || !isAllowedDownloadUrl(resolved)) {
                throw SecurityException("Blocked redirect to untrusted download URL")
            }
            currentReq = currentReq.newBuilder().url(resolved).build()
        }
        throw SecurityException("Too many redirects")
    }

    private fun readBoundedBytes(stream: java.io.InputStream, maxBytes: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var total = 0
        while (true) {
            val read = stream.read(buf)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private fun enrichWithChecksums(release: AppUpdateRelease, currentVersion: String): AppUpdateRelease {
        if (!release.expectedSha256.isNullOrBlank()) return release
        val url = release.checksumsAssetUrl ?: return release
        val sumsText = runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "text/plain")
                .header("User-Agent", "NuvioDeskRemote-Android/$currentVersion")
                .build()
            executeWithValidatedRedirects(req).use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val stream = resp.body?.byteStream() ?: return@runCatching null
                val bytes = readBoundedBytes(stream, MAX_NOTES_JSON_BYTES) ?: return@runCatching null
                String(bytes, Charsets.UTF_8)
            }
        }.getOrNull() ?: return release
        val resolvedSha = parseSha256Sums(sumsText, release.apkName) ?: return release
        return release.copy(expectedSha256 = resolvedSha)
    }

    private fun enrichWithLocalizedNotes(release: AppUpdateRelease, currentVersion: String): AppUpdateRelease {
        val url = release.notesAssetUrl ?: return release
        val jsonText = runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "NuvioDeskRemote-Android/$currentVersion")
                .build()
            executeWithValidatedRedirects(req).use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val stream = resp.body?.byteStream() ?: return@runCatching null
                val bytes = readBoundedBytes(stream, MAX_NOTES_JSON_BYTES) ?: return@runCatching null
                String(bytes, Charsets.UTF_8)
            }
        }.getOrNull() ?: return release
        val (enNotes, ptNotes) = parseLocalizedReleaseNotesJson(jsonText)
        return release.copy(
            notesEn = enNotes.ifEmpty { release.notesEn },
            notesPtBr = ptNotes.ifEmpty { release.notesPtBr },
        )
    }

    /** Downloads the release APK into `cacheDir/updates/`, enforcing mandatory SHA-256 verification. */
    suspend fun downloadApk(
        context: Context,
        release: AppUpdateRelease,
        currentVersion: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): ApkDownloadOutcome = withContext(Dispatchers.IO) {
        if (!isAllowedDownloadUrl(release.apkDownloadUrl) || release.expectedSha256.isNullOrBlank()) {
            return@withContext ApkDownloadOutcome.Failure(UpdateErrorReason.INTEGRITY)
        }
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        pruneOldApks(updatesDir)
        val safeName = release.apkName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".apk", ignoreCase = true)) it else "nuvio-deskremote-${release.version}.apk" }
        val finalFile = File(updatesDir, safeName)
        val partFile = File(updatesDir, "$safeName.part")
        streamAndVerifyApk(release, currentVersion, partFile, finalFile, onProgress)
    }

    private suspend fun streamAndVerifyApk(
        release: AppUpdateRelease,
        currentVersion: String,
        partFile: File,
        finalFile: File,
        onProgress: (Long, Long) -> Unit,
    ): ApkDownloadOutcome {
        val req = Request.Builder()
            .url(release.apkDownloadUrl)
            .header("User-Agent", "NuvioDeskRemote-Android/$currentVersion")
            .build()
        return try {
            executeWithValidatedRedirects(req).use { resp ->
                if (!resp.isSuccessful) return ApkDownloadOutcome.Failure(UpdateErrorReason.NETWORK)
                val body = resp.body ?: return ApkDownloadOutcome.Failure(UpdateErrorReason.NETWORK)
                val totalBytes = body.contentLength().takeIf { it > 0L } ?: release.apkSizeBytes
                val computedHex = copyWithDigest(body.byteStream(), partFile, totalBytes, onProgress)
                finalizeDownloadedApk(partFile, finalFile, release.expectedSha256, computedHex)
            }
        } catch (ce: CancellationException) {
            partFile.delete()
            throw ce
        } catch (_: SecurityException) {
            partFile.delete()
            ApkDownloadOutcome.Failure(UpdateErrorReason.INTEGRITY)
        } catch (io: IOException) {
            partFile.delete()
            val storageIssue = io.message?.contains("ENOSPC", ignoreCase = true) == true ||
                io.message?.contains("No space", ignoreCase = true) == true
            ApkDownloadOutcome.Failure(if (storageIssue) UpdateErrorReason.STORAGE else UpdateErrorReason.NETWORK)
        }
    }

    private suspend fun copyWithDigest(
        input: java.io.InputStream,
        partFile: File,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        var downloaded = 0L
        var lastEmitMs = 0L
        FileOutputStream(partFile).use { out ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                downloaded += read
                val now = System.currentTimeMillis()
                if (now - lastEmitMs >= PROGRESS_EMIT_INTERVAL_MS || downloaded == totalBytes) {
                    lastEmitMs = now
                    onProgress(downloaded, totalBytes)
                }
            }
            out.fd.sync()
        }
        onProgress(downloaded, totalBytes.coerceAtLeast(downloaded))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun finalizeDownloadedApk(
        partFile: File,
        finalFile: File,
        expectedSha256: String?,
        computedHex: String,
    ): ApkDownloadOutcome {
        if (expectedSha256.isNullOrBlank() || !expectedSha256.equals(computedHex, ignoreCase = true)) {
            partFile.delete()
            return ApkDownloadOutcome.Failure(UpdateErrorReason.INTEGRITY)
        }
        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            partFile.delete()
            return ApkDownloadOutcome.Failure(UpdateErrorReason.STORAGE)
        }
        return ApkDownloadOutcome.Success(file = finalFile, sha256Verified = true)
    }

    private fun pruneOldApks(updatesDir: File) {
        updatesDir.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
    }

    /** Opens Android's package installer via FileProvider, or requests unknown-sources permission first. */
    fun launchApkInstaller(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) return false
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settingsIntent) }
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }
}
