mod updater;

use qrcode::render::svg;
use qrcode::QrCode;
use sha2::{Digest, Sha256};
use std::collections::hash_map::RandomState;
use std::hash::{BuildHasher, Hasher};
use std::path::PathBuf;
use std::process::{Child, Command};
use std::sync::Mutex;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, State, WindowEvent};
use tauri_plugin_dialog::DialogExt;

#[cfg(target_os = "windows")]
use std::os::windows::process::CommandExt;

#[cfg(target_os = "windows")]
pub(crate) const CREATE_NO_WINDOW: u32 = 0x0800_0000;

pub(crate) struct CompanionProcess(pub(crate) Mutex<Option<Child>>);
pub(crate) struct LocalIpcToken(pub(crate) String);
pub(crate) struct CompanionHttpClient(pub(crate) reqwest::Client);

fn generate_local_ipc_token() -> String {
    let mut hasher = Sha256::new();
    for i in 0u64..8 {
        let mut h = RandomState::new().build_hasher();
        h.write_u64(i);
        hasher.update(h.finish().to_le_bytes());
    }
    hasher
        .finalize()
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect()
}

#[cfg(target_os = "windows")]
fn strip_verbatim_prefix(path: PathBuf) -> PathBuf {
    if let Some(raw) = path.to_str() {
        if let Some(stripped) = raw.strip_prefix(r"\\?\UNC\") {
            return PathBuf::from(format!(r"\\{}", stripped));
        }
        if let Some(stripped) = raw.strip_prefix(r"\\?\") {
            return PathBuf::from(stripped);
        }
    }
    path
}

#[cfg(not(target_os = "windows"))]
fn strip_verbatim_prefix(path: PathBuf) -> PathBuf {
    path
}

fn resolve_server_paths(app: &AppHandle) -> Option<(PathBuf, PathBuf, PathBuf)> {
    // 1. Installed mode: bundled resources next to the executable (resources/server/)
    if let Ok(raw_res_dir) = app.path().resource_dir() {
        let res_dir = strip_verbatim_prefix(raw_res_dir);
        let srv_dir = res_dir.join("resources").join("server");
        let entry = srv_dir.join("dist").join("index.js");
        if entry.exists() {
            let bundled_node = srv_dir.join("node.exe");
            let node = if bundled_node.exists() {
                bundled_node
            } else {
                PathBuf::from("node.exe")
            };
            return Some((node, entry, srv_dir));
        }
    }

    // 2. Development mode: ../server/dist/index.js relative to the repository root
    if let Ok(raw_cwd) = std::env::current_dir() {
        let cwd = strip_verbatim_prefix(raw_cwd);
        for ancestor in cwd.ancestors().take(4) {
            let srv_dir = ancestor.join("server");
            let entry = srv_dir.join("dist").join("index.js");
            if entry.exists() {
                return Some((PathBuf::from("node.exe"), entry, srv_dir));
            }
        }
    }

    None
}

fn spawn_companion(app: &AppHandle, ipc_token: &str) -> Option<Child> {
    let (node, entry, workdir) = resolve_server_paths(app)?;
    let mut cmd = Command::new(node);
    cmd.arg(entry)
        .current_dir(workdir)
        .env("NUVIO_COMPANION_PARENT_PID", std::process::id().to_string())
        .env("NUVIO_LOCAL_IPC_TOKEN", ipc_token);
    #[cfg(target_os = "windows")]
    {
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd.spawn().ok()
}

pub(crate) fn request_graceful_companion_shutdown(ipc_token: &str) {
    if let Ok(client) = reqwest::blocking::Client::builder()
        .timeout(std::time::Duration::from_millis(600))
        .redirect(reqwest::redirect::Policy::none())
        .build()
    {
        let _ = client
            .post("http://127.0.0.1:8787/api/v1/local/shutdown")
            .header("X-Nuvio-Local-Token", ipc_token)
            .body("{}")
            .send();
    }
}

pub(crate) fn stop_companion_locked(guard: &mut Option<Child>) {
    if let Some(mut child) = guard.take() {
        let deadline = std::time::Instant::now() + std::time::Duration::from_millis(350);
        loop {
            match child.try_wait() {
                Ok(Some(_)) => return,
                Ok(None) if std::time::Instant::now() < deadline => {
                    std::thread::sleep(std::time::Duration::from_millis(25));
                }
                _ => break,
            }
        }
        let _ = child.kill();
        let _ = child.wait();
    }
}

fn show_main_window(app: &AppHandle, focus_pairing: bool) {
    if let Some(win) = app.get_webview_window("main") {
        let _ = win.unminimize();
        let _ = win.show();
        let _ = win.set_focus();
        if focus_pairing {
            let _ = win.emit("focus-pairing", ());
        }
    }
}

#[tauri::command]
fn render_qr_svg(payload: String) -> Result<String, String> {
    let code = QrCode::new(payload.as_bytes()).map_err(|e| e.to_string())?;
    let svg_str = code
        .render::<svg::Color>()
        .min_dimensions(180, 180)
        .dark_color(svg::Color("#090b12"))
        .light_color(svg::Color("#ffffff"))
        .quiet_zone(true)
        .build();
    Ok(svg_str)
}

#[tauri::command]
async fn companion_request(
    http: State<'_, CompanionHttpClient>,
    ipc_token: State<'_, LocalIpcToken>,
    method: String,
    path: String,
    body: Option<String>,
) -> Result<String, String> {
    if !path.starts_with('/') {
        return Err("Invalid request path".to_string());
    }
    let url = format!("http://127.0.0.1:8787{}", path);

    let req = match method.to_uppercase().as_str() {
        "POST" => {
            let b = body.unwrap_or_else(|| "{}".to_string());
            http.0
                .post(&url)
                .header("Content-Type", "application/json")
                .header("X-Nuvio-Local-Token", &ipc_token.0)
                .body(b)
        }
        _ => http
            .0
            .get(&url)
            .header("X-Nuvio-Local-Token", &ipc_token.0),
    };

    let resp = req.send().await.map_err(|e| e.to_string())?;
    let status = resp.status();
    let text = resp.text().await.map_err(|e| e.to_string())?;
    if !status.is_success() {
        return Err(format!("Companion returned HTTP {}: {}", status.as_u16(), text));
    }
    Ok(text)
}

#[tauri::command]
fn restart_companion(
    app: AppHandle,
    state: State<'_, CompanionProcess>,
    ipc_token: State<'_, LocalIpcToken>,
) -> Result<(), String> {
    request_graceful_companion_shutdown(&ipc_token.0);
    let mut guard = state.0.lock().map_err(|e| e.to_string())?;
    stop_companion_locked(&mut guard);
    *guard = spawn_companion(&app, &ipc_token.0);
    Ok(())
}

#[tauri::command]
fn hide_to_tray(app: AppHandle) {
    if let Some(win) = app.get_webview_window("main") {
        let _ = win.hide();
    }
}

#[tauri::command]
async fn pick_nuvio_exe(app: AppHandle) -> Option<String> {
    tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title("Select Nuvio HTPC executable (Nuvio.exe)")
            .add_filter("Windows Executable", &["exe"])
            .blocking_pick_file()
            .and_then(|fp| fp.into_path().ok())
            .map(|p| strip_verbatim_prefix(p).to_string_lossy().to_string())
    })
    .await
    .ok()
    .flatten()
}

#[tauri::command]
fn get_autostart() -> bool {
    #[cfg(target_os = "windows")]
    {
        let out = Command::new("reg.exe")
            .args([
                "query",
                r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run",
                "/v",
                "NuvioDeskRemoteCompanion",
            ])
            .creation_flags(CREATE_NO_WINDOW)
            .output();
        return matches!(out, Ok(o) if o.status.success());
    }
    #[cfg(not(target_os = "windows"))]
    {
        false
    }
}

#[tauri::command]
#[cfg_attr(not(target_os = "windows"), allow(unused_variables))]
fn set_autostart(enabled: bool) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        if enabled {
            let exe = strip_verbatim_prefix(std::env::current_exe().map_err(|e| e.to_string())?);
            let val = format!("\"{}\" --minimized", exe.to_string_lossy());
            let status = Command::new("reg.exe")
                .args([
                    "add",
                    r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run",
                    "/v",
                    "NuvioDeskRemoteCompanion",
                    "/t",
                    "REG_SZ",
                    "/d",
                    &val,
                    "/f",
                ])
                .creation_flags(CREATE_NO_WINDOW)
                .status()
                .map_err(|e| e.to_string())?;
            if !status.success() {
                return Err("Failed to register Windows autostart entry".to_string());
            }
        } else {
            let _ = Command::new("reg.exe")
                .args([
                    "delete",
                    r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run",
                    "/v",
                    "NuvioDeskRemoteCompanion",
                    "/f",
                ])
                .creation_flags(CREATE_NO_WINDOW)
                .status();
        }
    }
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let start_minimized = std::env::args().any(|a| a == "--minimized");

    let mut builder = tauri::Builder::default();

    #[cfg(desktop)]
    {
        builder = builder.plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_main_window(app, false);
        }));
    }

    let ipc_token = generate_local_ipc_token();
    let http_client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(5))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .expect("failed to build local Companion HTTP client");

    let app = builder
        .plugin(tauri_plugin_dialog::init())
        .manage(CompanionProcess(Mutex::new(None)))
        .manage(LocalIpcToken(ipc_token))
        .manage(CompanionHttpClient(http_client))
        .manage(updater::UpdateState::new())
        .setup(move |app| {
            let handle = app.handle().clone();

            // Launch the bundled Node.js Companion server hidden alongside the desktop app
            let companion_state = app.state::<CompanionProcess>();
            let token_state = app.state::<LocalIpcToken>();
            let lock_res = companion_state.inner().0.lock();
            if let Ok(mut guard) = lock_res {
                *guard = spawn_companion(&handle, &token_state.0);
            }

            if start_minimized {
                if let Some(win) = app.get_webview_window("main") {
                    let _ = win.hide();
                }
            }

            let open_item = MenuItem::with_id(app, "open", "Open Nuvio DeskRemote", true, None::<&str>)?;
            let pair_item = MenuItem::with_id(app, "pair", "Pair Phone / QR Code", true, None::<&str>)?;
            let restart_item = MenuItem::with_id(app, "restart", "Restart Server", true, None::<&str>)?;
            let sep = PredefinedMenuItem::separator(app)?;
            let quit_item = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open_item, &pair_item, &restart_item, &sep, &quit_item])?;

            let mut tray_builder = TrayIconBuilder::new()
                .menu(&menu)
                .tooltip("Nuvio DeskRemote Companion")
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "open" => show_main_window(app, false),
                    "pair" => show_main_window(app, true),
                    "restart" => {
                        let token_state = app.state::<LocalIpcToken>();
                        request_graceful_companion_shutdown(&token_state.0);
                        let companion_state = app.state::<CompanionProcess>();
                        let lock_res = companion_state.inner().0.lock();
                        if let Ok(mut guard) = lock_res {
                            stop_companion_locked(&mut guard);
                            *guard = spawn_companion(app, &token_state.0);
                        }
                    }
                    "quit" => {
                        let token_state = app.state::<LocalIpcToken>();
                        request_graceful_companion_shutdown(&token_state.0);
                        let companion_state = app.state::<CompanionProcess>();
                        let lock_res = companion_state.inner().0.lock();
                        if let Ok(mut guard) = lock_res {
                            stop_companion_locked(&mut guard);
                        }
                        app.exit(0);
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        show_main_window(tray.app_handle(), false);
                    }
                });

            if let Some(icon) = app.default_window_icon() {
                tray_builder = tray_builder.icon(icon.clone());
            }
            let _tray = tray_builder.build(app)?;
            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .invoke_handler(tauri::generate_handler![
            render_qr_svg,
            companion_request,
            restart_companion,
            hide_to_tray,
            pick_nuvio_exe,
            get_autostart,
            set_autostart,
            updater::get_app_version,
            updater::check_github_update,
            updater::download_github_update,
            updater::cancel_github_update,
            updater::install_downloaded_update
        ])
        .build(tauri::generate_context!())
        .expect("error building Nuvio DeskRemote Companion");

    app.run(|app_handle, event| {
        if let tauri::RunEvent::Exit = event {
            let companion_state = app_handle.state::<CompanionProcess>();
            let has_child = companion_state
                .inner()
                .0
                .lock()
                .map(|g| g.is_some())
                .unwrap_or(false);
            if has_child {
                let token_state = app_handle.state::<LocalIpcToken>();
                request_graceful_companion_shutdown(&token_state.0);
                if let Ok(mut guard) = companion_state.inner().0.lock() {
                    stop_companion_locked(&mut guard);
                }
            }
        }
    });
}
