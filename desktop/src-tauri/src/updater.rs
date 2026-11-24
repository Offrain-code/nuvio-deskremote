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
const OFFICIAL_RELEASE_PATH_PREFIX: &str = "/offrain-code/nuvio-deskremote/releases/download/";
const RELEASE_NOTES_ASSET_NAME: &str = "release-notes.json";
const SHA256SUMS_ASSET_NAME: &str = "SHA256SUMS.txt";
const MAX_NOTES_JSON_BYTES: usize = 64 * 1024;
const MAX_CHANGELOG_ITEMS: usize = 8;
const MAX_REDIRECT_HOPS: usize = 5;

pub struct UpdateState {
    cancel: Arc<AtomicBool>,
    downloading: Arc<AtomicBool>,
    checked_release: Mutex<Option<UpdateCheckResult>>,
    downloaded_path: Mutex<Option<PathBuf>>,
    downloaded_sha256: Mutex<Option<String>>,
}

impl UpdateState {
    pub fn new() -> Self {
        Self {
            cancel: Arc::new(AtomicBool::new(false)),
            downloading: Arc::new(AtomicBool::new(false)),
            checked_release: Mutex::new(None),
            downloaded_path: Mutex::new(None),
            downloaded_sha256: Mutex::new(None),
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

fn compare_pre_release(a: &str, b: &str) -> CmpOrdering {
    let a_parts: Vec<&str> = a.split('.').collect();
    let b_parts: Vec<&str> = b.split('.').collect();
    for (pa, pb) in a_parts.iter().zip(b_parts.iter()) {
        let ord = match (pa.parse::<u64>(), pb.parse::<u64>()) {
            (Ok(na), Ok(nb)) => na.cmp(&nb),
            (Ok(_), Err(_)) => CmpOrdering::Less,
            (Err(_), Ok(_)) => CmpOrdering::Greater,
            (Err(_), Err(_)) => pa.to_ascii_lowercase().cmp(&pb.to_ascii_lowercase()),
        };
        if ord != CmpOrdering::Equal {
            return ord;
        }
    }
    a_parts.len().cmp(&b_parts.len())
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
                (Some(a), Some(b)) => compare_pre_release(a, b),
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
        Some((c, p)) => {
            let p_clean = p.trim();
            if p_clean.is_empty() || p_clean.split('.').any(|seg| seg.is_empty()) {
                return None;
            }
            (c, Some(p_clean.to_string()))
        }
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
    let Ok(parsed) = reqwest::Url::parse(raw.trim()) else {
        return false;
    };
    if parsed.scheme() != "https"
        || !parsed.username().is_empty()
        || parsed.password().is_some()
        || parsed.port().is_some()
    {
        return false;
    }
    let Some(host) = parsed.host_str().map(|h| h.to_ascii_lowercase()) else {
        return false;
    };
    if parsed.path_segments().is_some_and(|mut segs| segs.any(|s| s == "..")) {
        return false;
    }
    if host == "github.com" {
        return parsed
            .path()
            .to_ascii_lowercase()
            .starts_with(OFFICIAL_RELEASE_PATH_PREFIX);
    }
    matches!(
        host.as_str(),
        "objects.githubusercontent.com"
            | "release-assets.githubusercontent.com"
            | "github-releases.githubusercontent.com"
    )
}

fn build_safe_redirect_policy() -> reqwest::redirect::Policy {
    reqwest::redirect::Policy::custom(|attempt| {
        if attempt.previous().len() >= MAX_REDIRECT_HOPS {
            attempt.error("Too many HTTP redirects")
        } else if is_allowed_download_url(attempt.url().as_str()) {
            attempt.follow()
        } else {
            attempt.error("Blocked redirect to untrusted download host")
        }
    })
}

pub fn parse_sha256_sums(text: &str, asset_name: &str) -> Option<String> {
    let target = asset_name.trim();
    if target.is_empty() {
        return None;
    }
    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        let mut parts = trimmed.split_whitespace();
        let (Some(hash_raw), Some(file_token)) = (parts.next(), parts.next()) else {
            continue;
        };
        let file_raw = file_token.trim_start_matches('*');
        let file_base = file_raw.rsplit(['/', '\\']).next().unwrap_or(file_raw);
        if file_base.eq_ignore_ascii_case(target) {
            if let Some(valid) = extract_sha256(Some(hash_raw)) {
                return Some(valid);
            }
        }
    }
    None
}

fn read_bounded_bytes(
    resp: reqwest::blocking::Response,
    max_bytes: usize,
) -> Option<Vec<u8>> {
    let mut buf = Vec::new();
    resp.take((max_bytes + 1) as u64).read_to_end(&mut buf).ok()?;
    if buf.len() > max_bytes {
        return None;
    }
    Some(buf)
}

fn compute_file_sha256(path: &PathBuf) -> Result<String, String> {
    let mut file = File::open(path).map_err(|e| format!("Could not read installer file: {}", e))?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = file
            .read(&mut buf)
            .map_err(|e| format!("Could not hash installer file: {}", e))?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hasher
        .finalize()
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect())
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
    if !resp.status().is_success() || !is_allowed_download_url(resp.url().as_str()) {
        return None;
    }
    let bytes = read_bounded_bytes(resp, MAX_NOTES_JSON_BYTES)?;
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

fn fetch_sha256_from_sums_asset(
    client: &reqwest::blocking::Client,
    assets: &[GitHubAsset],
    target_asset_name: &str,
) -> Option<String> {
    let sums_asset = assets
        .iter()
        .find(|a| a.name.eq_ignore_ascii_case(SHA256SUMS_ASSET_NAME))?;
    if !is_allowed_download_url(&sums_asset.browser_download_url) {
        return None;
    }
    let resp = client
        .get(&sums_asset.browser_download_url)
        .header("Accept", "text/plain")
        .send()
        .ok()?;
    if !resp.status().is_success() || !is_allowed_download_url(resp.url().as_str()) {
        return None;
    }
    let bytes = read_bounded_bytes(resp, MAX_NOTES_JSON_BYTES)?;
    let text = std::str::from_utf8(&bytes).ok()?;
    parse_sha256_sums(text, target_asset_name)
}

fn check_github_update_blocking() -> Result<UpdateCheckResult, String> {
    let current_version = env!("CARGO_PKG_VERSION").to_string();
    let user_agent = format!("NuvioDeskRemote-Companion/{}", current_version);
    let api_client = reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(15))
        .redirect(reqwest::redirect::Policy::none())
        .user_agent(&user_agent)
        .build()
        .map_err(|e| e.to_string())?;
    let asset_client = reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(15))
        .redirect(build_safe_redirect_policy())
        .user_agent(&user_agent)
        .build()
        .map_err(|e| e.to_string())?;

    let resp = api_client
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

    let expected_sha256 = extract_sha256(exe_asset.digest.as_deref())
        .or_else(|| fetch_sha256_from_sums_asset(&asset_client, &release.assets, &exe_asset.name));

    let mut notes_en = parse_markdown_bullets(release.body.as_deref().unwrap_or(""));
    let mut notes_pt_br = Vec::new();
    if let Some((loc_en, loc_pt)) = fetch_localized_notes(&asset_client, &release.assets) {
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
        expected_sha256,
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
pub async fn check_github_update(
    state: State<'_, UpdateState>,
) -> Result<UpdateCheckResult, String> {
    let result = tauri::async_runtime::spawn_blocking(check_github_update_blocking)
        .await
        .map_err(|e| format!("Update check failed: {}", e))??;
    if let Ok(mut guard) = state.checked_release.lock() {
        *guard = if result.update_available {
            Some(result.clone())
        } else {
            None
        };
    }
    Ok(result)
}

#[tauri::command]
pub fn cancel_github_update(state: State<'_, UpdateState>) {
    state.cancel.store(true, Ordering::SeqCst);
}

#[tauri::command]
pub async fn download_github_update(
    app: AppHandle,
    state: State<'_, UpdateState>,
) -> Result<DownloadUpdateResult, String> {
    let verified_release = {
        let guard = state.checked_release.lock().map_err(|e| e.to_string())?;
        guard
            .clone()
            .filter(|r| r.update_available)
            .ok_or_else(|| "No verified update check result available".to_string())?
    };

    if !is_allowed_download_url(&verified_release.download_url) {
        return Err("Refused untrusted download URL".to_string());
    }
    let expected_hash = extract_sha256(verified_release.expected_sha256.as_deref())
        .ok_or_else(|| "SHA-256 verification failed: missing official checksum".to_string())?;

    if state
        .downloading
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        return Err("Update download already in progress".to_string());
    }
    state.cancel.store(false, Ordering::SeqCst);
    if let Ok(mut guard) = state.downloaded_path.lock() {
        *guard = None;
    }
    if let Ok(mut guard) = state.downloaded_sha256.lock() {
        *guard = None;
    }

    let cancel_flag = Arc::clone(&state.cancel);
    let downloading_flag = Arc::clone(&state.downloading);
    let safe_name = sanitize_installer_filename(
        &verified_release.asset_name,
        &verified_release.latest_version,
    );
    let expected_for_worker = expected_hash.clone();

    let result = tauri::async_runtime::spawn_blocking(move || {
        let _reset_guard = ResetFlag(downloading_flag);
        download_installer_blocking(
            app,
            cancel_flag,
            verified_release.download_url,
            safe_name,
            expected_for_worker,
        )
    })
    .await
    .map_err(|e| format!("Download worker failed: {}", e))??;

    if let Ok(mut guard) = state.downloaded_path.lock() {
        *guard = Some(PathBuf::from(&result.file_path));
    }
    if let Ok(mut guard) = state.downloaded_sha256.lock() {
        *guard = Some(expected_hash);
    }
    Ok(result)
}

fn resolve_update_dir() -> PathBuf {
    if let Ok(local_app_data) = std::env::var("LOCALAPPDATA") {
        let trimmed = local_app_data.trim();
        if !trimmed.is_empty() {
            return PathBuf::from(trimmed)
                .join("NuvioDeskRemote")
                .join("updates");
        }
    }
    std::env::temp_dir().join("NuvioDeskRemoteUpdate")
}

fn download_installer_blocking(
    app: AppHandle,
    cancel: Arc<AtomicBool>,
    download_url: String,
    safe_name: String,
    expected_sha256: String,
) -> Result<DownloadUpdateResult, String> {
    let dir = resolve_update_dir();
    fs::create_dir_all(&dir).map_err(|e| format!("Could not prepare update folder: {}", e))?;

    let final_path = dir.join(&safe_name);
    let part_path = dir.join(format!("{}.part", safe_name));

    let client = reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(300))
        .redirect(build_safe_redirect_policy())
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
    if !is_allowed_download_url(resp.url().as_str()) {
        return Err("Refused redirect to untrusted download host".to_string());
    }

    let total = resp.content_length().unwrap_or(0);
    let (computed_hex, downloaded) =
        match stream_file_with_sha256(&app, &cancel, &mut resp, &part_path, total) {
            Ok(pair) => pair,
            Err(err) => {
                let _ = fs::remove_file(&part_path);
                return Err(err);
            }
        };

    if downloaded == 0 || (total > 0 && downloaded != total) {
        let _ = fs::remove_file(&part_path);
        return Err("Incomplete installer download".to_string());
    }

    if !expected_sha256.eq_ignore_ascii_case(&computed_hex) {
        let _ = fs::remove_file(&part_path);
        return Err("SHA-256 verification failed".to_string());
    }

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
        sha256_verified: true,
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
    ipc_token: State<'_, crate::LocalIpcToken>,
    update: State<'_, UpdateState>,
) -> Result<(), String> {
    let (path, expected_sha256) = {
        let path_guard = update.downloaded_path.lock().map_err(|e| e.to_string())?;
        let hash_guard = update.downloaded_sha256.lock().map_err(|e| e.to_string())?;
        (
            path_guard
                .clone()
                .ok_or_else(|| "No verified installer is ready".to_string())?,
            hash_guard
                .clone()
                .ok_or_else(|| "SHA-256 verification failed: missing digest".to_string())?,
        )
    };
    if !path.exists() {
        return Err("Downloaded installer file was not found".to_string());
    }

    // Re-verify SHA-256 immediately before elevated execution to prevent TOCTOU tampering in %TEMP%
    let verify_path = path.clone();
    let actual_sha256 = tauri::async_runtime::spawn_blocking(move || compute_file_sha256(&verify_path))
        .await
        .map_err(|e| format!("SHA-256 verification worker failed: {}", e))??;
    if !actual_sha256.eq_ignore_ascii_case(&expected_sha256) {
        let _ = fs::remove_file(&path);
        if let Ok(mut guard) = update.downloaded_path.lock() {
            *guard = None;
        }
        if let Ok(mut guard) = update.downloaded_sha256.lock() {
            *guard = None;
        }
        return Err("SHA-256 verification failed before install".to_string());
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

    // Stop bundled Node.js Companion server cleanly only after UAC/installer launch succeeds
    let shutdown_token = ipc_token.0.clone();
    let _ = tauri::async_runtime::spawn_blocking(move || {
        crate::request_graceful_companion_shutdown(&shutdown_token);
    })
    .await;
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
        assert!(is_newer_version("0.5.0-rc.2", "0.5.0-rc.10"));
        assert!(!is_newer_version("0.5.0-rc.10", "0.5.0-rc.2"));
        assert!(parse_semver("0.5.0-").is_none());
        assert!(!is_newer_version("0.5.0", "0.5.0"));
        assert!(!is_newer_version("0.5.1", "0.5.0"));
    }

    #[test]
    fn url_allowlist_checks_https_and_official_repo_or_cdn() {
        assert!(is_allowed_download_url(
            "https://github.com/Offrain-code/nuvio-deskremote/releases/download/v0.5.0/setup.exe"
        ));
        assert!(is_allowed_download_url(
            "https://objects.githubusercontent.com/github-production-release-asset/123"
        ));
        assert!(is_allowed_download_url(
            "https://release-assets.githubusercontent.com/github-production-release-asset/123"
        ));
        assert!(!is_allowed_download_url(
            "https://github.com/attacker/evil-repo/releases/download/v1.0.0/setup.exe"
        ));
        assert!(!is_allowed_download_url(
            "https://github.com/Offrain-code/nuvio-deskremote/releases/download/../evil.exe"
        ));
        assert!(!is_allowed_download_url("http://github.com/Offrain-code/nuvio-deskremote/releases/download/v0.5.0/setup.exe"));
        assert!(!is_allowed_download_url("https://github.com.evil.com/test.exe"));
    }

    #[test]
    fn sha256sums_parser_matches_asset_case_insensitively() {
        let sums = "# comment\nmalformed-single-token\n0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  *Nuvio-DeskRemote-Setup.exe\n";
        assert_eq!(
            parse_sha256_sums(sums, "nuvio-deskremote-setup.exe").as_deref(),
            Some("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        );
        assert!(parse_sha256_sums(sums, "other.exe").is_none());
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
