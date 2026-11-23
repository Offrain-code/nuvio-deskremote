use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::cmp::Ordering as CmpOrdering;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, State};

const GITHUB_LATEST_RELEASE_URL: &str =
    "https://api.github.com/repos/Offrain-code/nuvio-deskremote/releases/latest";
const RELEASE_NOTES_ASSET_NAME: &str = "release-notes.json";
const MAX_NOTES_JSON_BYTES: usize = 64 * 1024;
const MAX_CHANGELOG_ITEMS: usize = 8;

pub struct UpdateState {
    cancel: Arc<AtomicBool>,
    downloading: Arc<AtomicBool>,
    downloaded_path: Mutex<Option<PathBuf>>,
}

impl UpdateState {
    pub fn new() -> Self {
        Self {
            cancel: Arc::new(AtomicBool::new(false)),
            downloading: Arc::new(AtomicBool::new(false)),
            downloaded_path: Mutex::new(None),
        }
    }
}

struct ResetFlag(Arc<AtomicBool>);

impl Drop for ResetFlag {
    fn drop(&mut self) {
        self.0.store(false, Ordering::SeqCst);
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SemVer {
    pub major: u64,
    pub minor: u64,
    pub patch: u64,
    pub pre: Option<String>,
}

impl PartialOrd for SemVer {
    fn partial_cmp(&self, other: &Self) -> Option<CmpOrdering> {
        Some(self.cmp(other))
    }
}

impl Ord for SemVer {
    fn cmp(&self, other: &Self) -> CmpOrdering {
        self.major
            .cmp(&other.major)
            .then_with(|| self.minor.cmp(&other.minor))
            .then_with(|| self.patch.cmp(&other.patch))
            .then_with(|| match (&self.pre, &other.pre) {
                (None, None) => CmpOrdering::Equal,
                (None, Some(_)) => CmpOrdering::Greater,
                (Some(_), None) => CmpOrdering::Less,
                (Some(a), Some(b)) => a.to_ascii_lowercase().cmp(&b.to_ascii_lowercase()),
            })
    }
}

#[derive(Deserialize)]
struct GitHubAsset {
    name: String,
    #[serde(default)]
    size: u64,
    #[serde(default)]
    digest: Option<String>,
    browser_download_url: String,
}

#[derive(Deserialize)]
struct GitHubRelease {
    tag_name: String,
    #[serde(default)]
    draft: bool,
    #[serde(default)]
    prerelease: bool,
    #[serde(default)]
    body: Option<String>,
    #[serde(default)]
    html_url: String,
    #[serde(default)]
    assets: Vec<GitHubAsset>,
}

#[derive(Deserialize)]
struct LocalizedNotesJson {
    #[serde(default)]
    en: Vec<String>,
    #[serde(default, rename = "pt-BR")]
    pt_br: Vec<String>,
    #[serde(default, rename = "pt_BR")]
    pt_br_alt: Vec<String>,
    #[serde(default)]
    pt: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct UpdateCheckResult {
    pub update_available: bool,
    pub current_version: String,
    pub latest_version: String,
    pub tag_name: String,
    pub asset_name: String,
    pub download_url: String,
    pub asset_size: u64,
    pub expected_sha256: Option<String>,
    pub notes_en: Vec<String>,
    pub notes_pt_br: Vec<String>,
    pub html_url: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct UpdateProgressPayload {
    pub downloaded: u64,
    pub total: u64,
    pub percent: u8,
}

#[derive(Debug, Clone, Serialize)]
pub struct DownloadUpdateResult {
    pub file_path: String,
    pub sha256_verified: bool,
}

pub fn parse_semver(raw: &str) -> Option<SemVer> {
    let trimmed = raw
        .trim()
        .trim_start_matches(['v', 'V']);
    let without_build = trimmed.split('+').next().unwrap_or("");
    let (core, pre) = match without_build.split_once('-') {
        Some((c, p)) if !p.trim().is_empty() => (c, Some(p.trim().to_string())),
        Some((c, _)) => (c, None),
        None => (without_build, None),
    };
    let parts: Vec<&str> = core.split('.').collect();
    if parts.len() < 2 || parts.len() > 3 {
        return None;
    }
    let major = parts[0].parse::<u64>().ok()?;
    let minor = parts[1].parse::<u64>().ok()?;
    let patch = if parts.len() == 3 {
        parts[2].parse::<u64>().ok()?
    } else {
        0
    };
    Some(SemVer {
        major,
        minor,
        patch,
        pre,
    })
}

pub fn is_newer_version(current: &str, candidate: &str) -> bool {
    match (parse_semver(current), parse_semver(candidate)) {
        (Some(cur), Some(next)) => next > cur,
        _ => false,
    }
}

pub fn is_allowed_download_url(raw: &str) -> bool {
    let trimmed = raw.trim();
    let Some(rest) = trimmed.strip_prefix("https://") else {
        return false;
    };
    let host_port = rest.split(['/', '?', '#']).next().unwrap_or("");
    if host_port.is_empty() || host_port.contains('@') {
        return false;
    }
    let host = host_port.split(':').next().unwrap_or("").to_ascii_lowercase();
    host == "github.com"
        || host == "api.github.com"
        || host == "githubusercontent.com"
        || host.ends_with(".githubusercontent.com")
}

fn extract_sha256(digest: Option<&str>) -> Option<String> {
    let raw = digest?.trim();
    let hex = raw.strip_prefix("sha256:").unwrap_or(raw);
    if hex.len() == 64 && hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        Some(hex.to_ascii_lowercase())
    } else {
        None
    }
}

fn strip_markdown_links(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut rest = input;
    while let Some(open_br) = rest.find('[') {
        out.push_str(&rest[..open_br]);
        let after_open = &rest[open_br + 1..];
        let Some(close_br) = after_open.find("](") else {
            out.push('[');
            rest = after_open;
            continue;
        };
        let label = &after_open[..close_br];
        let after_mid = &after_open[close_br + 2..];
        let Some(close_paren) = after_mid.find(')') else {
            out.push('[');
            rest = after_open;
            continue;
        };
        out.push_str(label);
        rest = &after_mid[close_paren + 1..];
    }
    out.push_str(rest);
    out
}

pub fn parse_markdown_bullets(body: &str) -> Vec<String> {
    let mut items = Vec::new();
    for line in body.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty()
            || trimmed.starts_with('#')
            || trimmed.starts_with("<!--")
            || trimmed.to_ascii_lowercase().starts_with("**full changelog**")
        {
            continue;
        }
        let stripped = if let Some(s) = trimmed.strip_prefix("- ") {
            s
        } else if let Some(s) = trimmed.strip_prefix("* ") {
            s
        } else if let Some(s) = trimmed.strip_prefix("\u{2022} ") {
            s
        } else {
            continue;
        };
        let mut cleaned = strip_markdown_links(stripped);
        if let Some(idx) = cleaned.find(" by @") {
            if cleaned[idx..].contains(" in http") {
                cleaned.truncate(idx);
            }
        }
        let normalized = cleaned
            .replace("**", "")
            .replace('`', "")
            .trim()
            .to_string();
        if !normalized.is_empty() && !items.contains(&normalized) {
            items.push(normalized);
            if items.len() >= MAX_CHANGELOG_ITEMS {
                break;
            }
        }
    }
    items
}

fn sanitize_notes_list(raw: Vec<String>) -> Vec<String> {
    raw.into_iter()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .take(MAX_CHANGELOG_ITEMS)
        .collect()
}

fn sanitize_installer_filename(raw: &str, version: &str) -> String {
    let cleaned: String = raw
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-') {
                c
            } else {
                '_'
            }
        })
        .collect();
    if cleaned.len() > 4 && cleaned.to_ascii_lowercase().ends_with(".exe") {
        cleaned
    } else {
        format!("Nuvio-DeskRemote-Setup-{}.exe", version)
    }
}

fn find_windows_installer_asset(assets: &[GitHubAsset]) -> Option<&GitHubAsset> {
    let mut fallback: Option<&GitHubAsset> = None;
    for asset in assets {
        let lower = asset.name.to_ascii_lowercase();
        if !lower.ends_with(".exe") {
            continue;
        }
        if lower.contains("setup") {
            return Some(asset);
        }
        if fallback.is_none() {
            fallback = Some(asset);
        }
    }
    fallback
}

fn fetch_localized_notes(
    client: &reqwest::blocking::Client,
    assets: &[GitHubAsset],
) -> Option<(Vec<String>, Vec<String>)> {
    let notes_asset = assets
        .iter()
        .find(|a| a.name.eq_ignore_ascii_case(RELEASE_NOTES_ASSET_NAME))?;
    if !is_allowed_download_url(&notes_asset.browser_download_url) {
        return None;
    }
    let resp = client
        .get(&notes_asset.browser_download_url)
        .header("Accept", "application/json")
        .send()
        .ok()?;
    if !resp.status().is_success() {
        return None;
    }
    let bytes = resp.bytes().ok()?;
    if bytes.len() > MAX_NOTES_JSON_BYTES {
        return None;
    }
    let parsed: LocalizedNotesJson = serde_json::from_slice(&bytes).ok()?;
    let en = sanitize_notes_list(parsed.en);
    let pt = if !parsed.pt_br.is_empty() {
        sanitize_notes_list(parsed.pt_br)
    } else if !parsed.pt_br_alt.is_empty() {
        sanitize_notes_list(parsed.pt_br_alt)
    } else {
        sanitize_notes_list(parsed.pt)
    };
    Some((en, pt))
}

fn check_github_update_blocking() -> Result<UpdateCheckResult, String> {
    let current_version = env!("CARGO_PKG_VERSION").to_string();
    let user_agent = format!("NuvioDeskRemote-Companion/{}", current_version);
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(12))
        .user_agent(user_agent)
        .build()
        .map_err(|e| e.to_string())?;

    let resp = client
        .get(GITHUB_LATEST_RELEASE_URL)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .send()
        .map_err(|e| format!("GitHub request failed: {}", e))?;

    if !resp.status().is_success() {
        return Err(format!("GitHub API returned HTTP {}", resp.status()));
    }
    let raw_json = resp.text().map_err(|e| e.to_string())?;
    let release: GitHubRelease = serde_json::from_str(&raw_json).map_err(|e| e.to_string())?;

    if release.draft || release.prerelease {
        return Ok(no_update_result(current_version));
    }
    let latest_version = release
        .tag_name
        .trim()
        .trim_start_matches(['v', 'V'])
        .to_string();

    if latest_version.is_empty() || !is_newer_version(&current_version, &latest_version) {
        return Ok(no_update_result(current_version));
    }

    let Some(exe_asset) = find_windows_installer_asset(&release.assets) else {
        return Ok(no_update_result(current_version));
    };
    if !is_allowed_download_url(&exe_asset.browser_download_url) {
        return Err("Untrusted download URL in GitHub release".to_string());
    }

    let mut notes_en = parse_markdown_bullets(release.body.as_deref().unwrap_or(""));
    let mut notes_pt_br = Vec::new();
    if let Some((loc_en, loc_pt)) = fetch_localized_notes(&client, &release.assets) {
        if !loc_en.is_empty() {
            notes_en = loc_en;
        }
        if !loc_pt.is_empty() {
            notes_pt_br = loc_pt;
        }
    }

    Ok(UpdateCheckResult {
        update_available: true,
        current_version,
        latest_version,
        tag_name: release.tag_name,
        asset_name: exe_asset.name.clone(),
        download_url: exe_asset.browser_download_url.clone(),
        asset_size: exe_asset.size,
        expected_sha256: extract_sha256(exe_asset.digest.as_deref()),
        notes_en,
        notes_pt_br,
        html_url: release.html_url,
    })
}

fn no_update_result(current_version: String) -> UpdateCheckResult {
    UpdateCheckResult {
        update_available: false,
        latest_version: current_version.clone(),
        tag_name: format!("v{}", current_version),
        current_version,
        asset_name: String::new(),
        download_url: String::new(),
        asset_size: 0,
        expected_sha256: None,
        notes_en: Vec::new(),
        notes_pt_br: Vec::new(),
        html_url: String::new(),
    }
}

#[tauri::command]
pub fn get_app_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[tauri::command]
pub async fn check_github_update() -> Result<UpdateCheckResult, String> {
    tauri::async_runtime::spawn_blocking(check_github_update_blocking)
        .await
        .map_err(|e| format!("Update check failed: {}", e))?
}

#[tauri::command]
pub fn cancel_github_update(state: State<'_, UpdateState>) {
    state.cancel.store(true, Ordering::SeqCst);
}

#[tauri::command]
pub async fn download_github_update(
    app: AppHandle,
    state: State<'_, UpdateState>,
    download_url: String,
    asset_name: String,
    version: String,
    expected_sha256: Option<String>,
) -> Result<DownloadUpdateResult, String> {
    if !is_allowed_download_url(&download_url) {
        return Err("Refused untrusted download URL".to_string());
    }
    if state
        .downloading
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        return Err("Update download already in progress".to_string());
    }
    state.cancel.store(false, Ordering::SeqCst);

    let cancel_flag = Arc::clone(&state.cancel);
    let downloading_flag = Arc::clone(&state.downloading);
    let safe_name = sanitize_installer_filename(&asset_name, &version);
    let expected_hash = extract_sha256(expected_sha256.as_deref());

    let result = tauri::async_runtime::spawn_blocking(move || {
        let _reset_guard = ResetFlag(downloading_flag);
        download_installer_blocking(app, cancel_flag, download_url, safe_name, expected_hash)
    })
    .await
    .map_err(|e| format!("Download worker failed: {}", e))??;

    if let Ok(mut guard) = state.downloaded_path.lock() {
        *guard = Some(PathBuf::from(&result.file_path));
    }
    Ok(result)
}

fn download_installer_blocking(
    app: AppHandle,
    cancel: Arc<AtomicBool>,
    download_url: String,
    safe_name: String,
    expected_sha256: Option<String>,
) -> Result<DownloadUpdateResult, String> {
    let dir = std::env::temp_dir().join("NuvioDeskRemoteUpdate");
    fs::create_dir_all(&dir).map_err(|e| format!("Could not prepare update folder: {}", e))?;

    let final_path = dir.join(&safe_name);
    let part_path = dir.join(format!("{}.part", safe_name));

    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(300))
        .user_agent(format!("NuvioDeskRemote-Companion/{}", env!("CARGO_PKG_VERSION")))
        .build()
        .map_err(|e| e.to_string())?;

    let mut resp = client
        .get(&download_url)
        .send()
        .map_err(|e| format!("Download failed: {}", e))?;
    if !resp.status().is_success() {
        return Err(format!("Download failed with HTTP {}", resp.status()));
    }

    let total = resp.content_length().unwrap_or(0);
    let computed_hex = match stream_file_with_sha256(&app, &cancel, &mut resp, &part_path, total) {
        Ok((hex, downloaded)) => (hex, downloaded),
        Err(err) => {
            let _ = fs::remove_file(&part_path);
            return Err(err);
        }
    };
    let (computed_hex, downloaded) = computed_hex;

    let sha256_verified = match expected_sha256 {
        Some(ref expected) => {
            if !expected.eq_ignore_ascii_case(&computed_hex) {
                let _ = fs::remove_file(&part_path);
                return Err("SHA-256 verification failed".to_string());
            }
            true
        }
        None => false,
    };

    let _ = fs::remove_file(&final_path);
    fs::rename(&part_path, &final_path).map_err(|e| {
        let _ = fs::remove_file(&part_path);
        format!("Failed to finalize installer file: {}", e)
    })?;

    let _ = app.emit(
        "update-download-progress",
        UpdateProgressPayload {
            downloaded,
            total: total.max(downloaded),
            percent: 100,
        },
    );

    Ok(DownloadUpdateResult {
        file_path: final_path.to_string_lossy().to_string(),
        sha256_verified,
    })
}

fn stream_file_with_sha256(
    app: &AppHandle,
    cancel: &Arc<AtomicBool>,
    resp: &mut reqwest::blocking::Response,
    part_path: &PathBuf,
    total: u64,
) -> Result<(String, u64), String> {
    let mut out = File::create(part_path).map_err(|e| format!("Cannot create file: {}", e))?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    let mut downloaded: u64 = 0;
    let mut last_emit = Instant::now();

    loop {
        if cancel.load(Ordering::SeqCst) {
            return Err("cancelled".to_string());
        }
        let n = resp
            .read(&mut buf)
            .map_err(|e| format!("Network error during download: {}", e))?;
        if n == 0 {
            break;
        }
        out.write_all(&buf[..n])
            .map_err(|e| format!("Disk write error: {}", e))?;
        hasher.update(&buf[..n]);
        downloaded += n as u64;

        if last_emit.elapsed() >= Duration::from_millis(90) || (total > 0 && downloaded >= total) {
            last_emit = Instant::now();
            let pct = if total > 0 {
                ((downloaded.saturating_mul(100)) / total).min(99) as u8
            } else {
                0
            };
            let _ = app.emit(
                "update-download-progress",
                UpdateProgressPayload {
                    downloaded,
                    total,
                    percent: pct,
                },
            );
        }
    }

    out.flush().map_err(|e| e.to_string())?;
    let computed_hex: String = hasher
        .finalize()
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect();
    Ok((computed_hex, downloaded))
}

#[tauri::command]
#[cfg_attr(not(target_os = "windows"), allow(unused_variables))]
pub async fn install_downloaded_update(
    app: AppHandle,
    companion: State<'_, crate::CompanionProcess>,
    update: State<'_, UpdateState>,
) -> Result<(), String> {
    let path = {
        let guard = update.downloaded_path.lock().map_err(|e| e.to_string())?;
        guard
            .clone()
            .ok_or_else(|| "No verified installer is ready".to_string())?
    };
    if !path.exists() {
        return Err("Downloaded installer file was not found".to_string());
    }

    #[cfg(target_os = "windows")]
    {
        let installer_path = path.clone();
        let launched = tauri::async_runtime::spawn_blocking(move || {
            use std::os::windows::process::CommandExt;
            let status = std::process::Command::new("powershell.exe")
                .args([
                    "-NoProfile",
                    "-NonInteractive",
                    "-WindowStyle",
                    "Hidden",
                    "-Command",
                    "Start-Process -FilePath $env:NUVIO_UPDATE_INSTALLER",
                ])
                .env("NUVIO_UPDATE_INSTALLER", &installer_path)
                .creation_flags(crate::CREATE_NO_WINDOW)
                .status()
                .map_err(|e| format!("Failed to start installer: {}", e))?;
            if status.success() {
                Ok(())
            } else {
                Err("Installer cancelled or elevation denied".to_string())
            }
        })
        .await
        .map_err(|e| format!("Installer launch worker failed: {}", e))?;
        launched?;
    }

    // Stop bundled Node.js Companion server only after UAC/installer launch succeeds
    if let Ok(mut guard) = companion.0.lock() {
        crate::stop_companion_locked(&mut guard);
    }

    app.exit(0);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn semver_ordering_and_prefixes() {
        assert!(is_newer_version("0.4.3", "0.5.0"));
        assert!(is_newer_version("v0.4.3", "v0.10.0"));
        assert!(is_newer_version("0.5.0-rc1", "0.5.0"));
        assert!(!is_newer_version("0.5.0", "0.5.0"));
        assert!(!is_newer_version("0.5.1", "0.5.0"));
    }

    #[test]
    fn url_allowlist_checks_https_and_github_hosts() {
        assert!(is_allowed_download_url(
            "https://github.com/Offrain-code/nuvio-deskremote/releases/download/v0.5.0/setup.exe"
        ));
        assert!(is_allowed_download_url(
            "https://objects.githubusercontent.com/asset/123"
        ));
        assert!(!is_allowed_download_url("http://github.com/test.exe"));
        assert!(!is_allowed_download_url("https://github.com.evil.com/test.exe"));
    }

    #[test]
    fn markdown_bullet_extraction_cleans_pr_links() {
        let body = "## What's Changed\n- Auto-update modal by @Offrain-code in https://github.com/x/y/pull/1\n* Fixed [firewall](https://example.com) rules\n**Full Changelog**: https://github.com/x/y";
        assert_eq!(
            parse_markdown_bullets(body),
            vec![
                "Auto-update modal".to_string(),
                "Fixed firewall rules".to_string()
            ]
        );
    }
}
