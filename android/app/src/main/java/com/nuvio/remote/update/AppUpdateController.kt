package com.nuvio.remote.update

import android.content.Context
import com.nuvio.remote.data.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Inline status shown inside Settings after a manual update check. */
enum class ManualUpdateCheckStatus {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    FAILED,
}

/**
 * Coordinates GitHub Releases checks, cooperative APK downloads, "Later" snooze persistence,
 * and FileProvider installation for [com.nuvio.remote.ui.components.AutoUpdateDialog].
 */
class AppUpdateController(
    private val context: Context,
    private val appPrefs: AppPrefs,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var lastReadyApk: java.io.File? = null
    private var lastReadySha256Verified: Boolean = false

    val installedVersion: String = resolveInstalledVersionName(context)

    private val _uiState = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Hidden)
    val uiState: StateFlow<AppUpdateUiState> = _uiState.asStateFlow()

    private val _manualCheckStatus = MutableStateFlow(ManualUpdateCheckStatus.IDLE)
    val manualCheckStatus: StateFlow<ManualUpdateCheckStatus> = _manualCheckStatus.asStateFlow()

    /** Checks GitHub Releases once on startup or on demand from Settings. */
    fun checkForUpdates(manual: Boolean) {
        if (_uiState.value is AppUpdateUiState.Downloading) return
        checkJob?.cancel()
        if (manual) _manualCheckStatus.value = ManualUpdateCheckStatus.CHECKING
        checkJob = scope.launch {
            val result = AppUpdateService.checkLatestRelease(installedVersion)
            result.fold(
                onSuccess = { release -> handleCheckSuccess(release, manual) },
                onFailure = {
                    if (manual) _manualCheckStatus.value = ManualUpdateCheckStatus.FAILED
                },
            )
        }
    }

    private fun handleCheckSuccess(release: AppUpdateRelease?, manual: Boolean) {
        if (release == null) {
            if (manual) _manualCheckStatus.value = ManualUpdateCheckStatus.UP_TO_DATE
            return
        }
        val shouldShow = manual || shouldShowAutoPrompt(
            latestVersion = release.version,
            dismissedVersion = appPrefs.dismissedUpdateVersion,
            dismissedAtMs = appPrefs.dismissedUpdateAtMs,
            nowMs = System.currentTimeMillis(),
        )
        if (manual) _manualCheckStatus.value = ManualUpdateCheckStatus.IDLE
        if (shouldShow) {
            _uiState.value = AppUpdateUiState.Available(
                currentVersion = installedVersion,
                release = release,
            )
        }
    }

    /** Starts streaming the release APK with live progress updates. */
    fun startDownload() {
        val release = currentRelease() ?: return
        downloadJob?.cancel()
        _uiState.value = AppUpdateUiState.Downloading(
            currentVersion = installedVersion,
            release = release,
            downloadedBytes = 0L,
            totalBytes = release.apkSizeBytes,
        )
        downloadJob = scope.launch {
            val outcome = AppUpdateService.downloadApk(
                context = context,
                release = release,
                currentVersion = installedVersion,
            ) { downloaded, total ->
                val cur = _uiState.value
                if (cur is AppUpdateUiState.Downloading) {
                    _uiState.value = cur.copy(downloadedBytes = downloaded, totalBytes = total)
                }
            }
            _uiState.value = when (outcome) {
                is ApkDownloadOutcome.Success -> {
                    lastReadyApk = outcome.file
                    lastReadySha256Verified = outcome.sha256Verified
                    AppUpdateUiState.ReadyToInstall(
                        currentVersion = installedVersion,
                        release = release,
                        apkFile = outcome.file,
                        sha256Verified = outcome.sha256Verified,
                    )
                }
                is ApkDownloadOutcome.Failure -> AppUpdateUiState.Error(
                    currentVersion = installedVersion,
                    release = release,
                    reason = outcome.reason,
                )
            }
        }
    }

    /** Cancels an active download and returns to the Available modal state. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val release = currentRelease()
        _uiState.value = if (release != null) {
            AppUpdateUiState.Available(currentVersion = installedVersion, release = release)
        } else {
            AppUpdateUiState.Hidden
        }
    }

    /** Dismisses the popup and snoozes automatic prompts for this version for 24 hours. */
    fun dismissLater() {
        if (_uiState.value is AppUpdateUiState.Downloading) return
        currentRelease()?.let { rel ->
            appPrefs.dismissedUpdateVersion = rel.version
            appPrefs.dismissedUpdateAtMs = System.currentTimeMillis()
        }
        _uiState.value = AppUpdateUiState.Hidden
    }

    /** Closes an error or ready prompt without persisting a 24h snooze. */
    fun closeModal() {
        if (_uiState.value is AppUpdateUiState.Downloading) return
        _uiState.value = AppUpdateUiState.Hidden
    }

    /** Retries installation if APK is already cached after permission grant, otherwise retries download. */
    fun retry() {
        val state = _uiState.value as? AppUpdateUiState.Error ?: return
        val cachedApk = lastReadyApk?.takeIf { it.exists() }
        val canReuseCached = state.reason == UpdateErrorReason.INSTALL_PERMISSION &&
            state.release != null &&
            cachedApk != null
        if (canReuseCached) {
            _uiState.value = AppUpdateUiState.ReadyToInstall(
                currentVersion = state.currentVersion,
                release = state.release!!,
                apkFile = cachedApk!!,
                sha256Verified = lastReadySha256Verified,
            )
            installReadyApk()
            return
        }
        if (state.release != null) {
            startDownload()
        } else {
            checkForUpdates(manual = true)
        }
    }

    /** Invokes Android's package installer for the verified downloaded APK. */
    fun installReadyApk() {
        val state = _uiState.value as? AppUpdateUiState.ReadyToInstall ?: return
        val launched = runCatching {
            AppUpdateService.launchApkInstaller(context, state.apkFile)
        }.getOrDefault(false)
        if (!launched && !context.packageManager.canRequestPackageInstalls()) {
            _uiState.value = AppUpdateUiState.Error(
                currentVersion = state.currentVersion,
                release = state.release,
                reason = UpdateErrorReason.INSTALL_PERMISSION,
            )
        }
    }

    private fun currentRelease(): AppUpdateRelease? = when (val cur = _uiState.value) {
        is AppUpdateUiState.Available -> cur.release
        is AppUpdateUiState.Downloading -> cur.release
        is AppUpdateUiState.ReadyToInstall -> cur.release
        is AppUpdateUiState.Error -> cur.release
        AppUpdateUiState.Hidden -> null
    }

    fun dispose() {
        checkJob?.cancel()
        downloadJob?.cancel()
        scope.cancel()
    }
}
