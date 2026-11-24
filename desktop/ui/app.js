const invoke = window.__TAURI__?.core?.invoke;
const listen = window.__TAURI__?.event?.listen;

const LANG_STORAGE_KEY = 'nuvio_companion_lang';
const DISMISSED_UPDATE_KEY = 'nuvio_dismissed_update';
const UPDATE_DISMISS_COOLDOWN_MS = 24 * 60 * 60 * 1000;

const I18N = {
  en: {
    checkUpdateTitle: 'Check for updates',
    upToDateToast: (v) => `v${v} • Up to date`,
    checkingUpdates: 'Checking…',
    updateKickerAvailable: 'GITHUB RELEASE • AUTO-UPDATE',
    updateKickerProgress: 'INSTALLER DOWNLOAD (.EXE)',
    updateKickerError: 'UPDATE ERROR',
    updateTitleAvailable: 'Update available',
    updateTitleDownloading: 'Downloading Windows installer…',
    updateTitleReady: 'Ready to install',
    updateTitleError: 'Update download failed',
    updateSubtitleAvailable: 'A new official Windows Companion release is ready to install.',
    updateNotesHeading: (v) => `WHAT'S NEW IN v${v}`,
    updatePreserveHint: 'Paired phones, LAN_ONLY PIN policy, and Nuvio.exe path are preserved automatically.',
    updateFallbackNote: 'Stability improvements and refinements for Nuvio DeskRemote Companion.',
    updateLaterBtn: 'Later',
    updateNowBtn: 'Update now',
    updateCancelBtn: 'Cancel',
    updateInstallBtn: 'Install & Restart',
    updateCloseBtn: 'Close',
    updateRetryBtn: 'Retry',
    updateDownloadingSub: 'Downloading Windows installer (.exe)…',
    updateReadySub: 'Ready to install • The Companion will launch the installer and restart.',
    updateErrorNetwork:
      'Could not download the package from GitHub Releases. Check your internet connection and try again.',
    updateErrorIntegrity:
      'SHA-256 verification failed and the downloaded file was discarded. Please try again.',
    nativeWin: 'Native Windows',
    brandSub: 'HTPC Remote + Trackpad + Screen Share over LAN',
    restartServerTitle: 'Restart server',
    hideTrayTitle: 'Minimize to system tray',
    pairingHeading: 'QUICK PAIRING (QR + PIN)',
    generatingQr: 'Generating QR…',
    pinLabel: '6-DIGIT PIN (LAN)',
    pairingHint: 'Scan the QR Code in the Android app or enter this PIN on the same Wi-Fi.',
    refreshPinBtn: '↻ Generate new code (120s)',
    servicesHeading: 'SERVICE & NETWORK STATUS',
    companionServer: 'Companion Server',
    nuvioBridge: 'Nuvio HTPC Bridge',
    openNuvioBtn: 'Open Nuvio',
    localNetwork: 'Local Network',
    devicesHeading: 'PAIRED DEVICES',
    emptyDevices: 'No phones paired yet. Scan the QR Code on the left to get started.',
    pcControlsHeading: 'COMPUTER CONTROLS',
    nuvioExeLabel: 'NUVIO HTPC PATH',
    browseBtn: 'Browse…',
    autostartTitle: 'Start with Windows',
    autostartSub: 'Starts minimized in the system tray when signing in to Windows',
    tunnelTitle: 'Remote Access (away from home)',
    copyUrlBtn: 'Copy URL',
    copiedBtn: 'Copied',
    lanOnlyNote:
      'Security: 6-digit PIN and new device pairing remain restricted to LAN (LAN_ONLY). ' +
      'Only phones already paired on local Wi-Fi can connect over the tunnel.',
    bridgeWarningHtml:
      'Nuvio HTPC is not open or waiting to link under <strong>Settings &gt; Mobile Remote</strong>.',
    headerOnline: 'Server Active',
    headerStarting: 'Starting server…',
    headerRestarting: 'Restarting…',
    companionActive: (port) => `Active • Port ${port}`,
    companionWaiting: 'Waiting for port 8787',
    bridgeConnected: 'Connected • Port 47321',
    bridgePinRequired: 'Waiting for PIN',
    bridgeUnlinked: 'Not linked',
    lanIp: (ip) => `Wi-Fi IP: ${ip || 'not detected'}`,
    expiresIn: (mmss) => `Expires in ${mmss} (120s)`,
    deviceCount: (n) => `${n} ${n === 1 ? 'phone' : 'phones'}`,
    androidDevice: 'Android Device',
    recently: 'recently',
    lastSeen: (ts) => `Last seen: ${ts}`,
    revoke: 'Revoke',
    qrReady: 'QR ready',
    exeConfigured: 'Configured',
    exeAutodetected: 'Auto-detected',
    exeNotFound: 'Not found',
    exeBrowseHint: 'Click Browse… to locate Nuvio.exe',
    tunnelOnSub: 'On • Cloudflare tunnel active (PIN stays LAN_ONLY)',
    tunnelOffSub: 'Off by default • Restricted to local Wi-Fi network',
    tunnelGenerating: 'Generating Cloudflare tunnel URL…',
    locale: 'en-US',
  },
  'pt-BR': {
    checkUpdateTitle: 'Verificar atualizações',
    upToDateToast: (v) => `v${v} • Atualizado`,
    checkingUpdates: 'Verificando…',
    updateKickerAvailable: 'GITHUB RELEASE • ATUALIZAÇÃO',
    updateKickerProgress: 'DOWNLOAD DO INSTALADOR (.EXE)',
    updateKickerError: 'ERRO DE ATUALIZAÇÃO',
    updateTitleAvailable: 'Atualização disponível',
    updateTitleDownloading: 'Baixando instalador do Windows…',
    updateTitleReady: 'Pronto para instalar',
    updateTitleError: 'Falha ao baixar atualização',
    updateSubtitleAvailable: 'Uma nova versão oficial do Companion para Windows está pronta para instalar.',
    updateNotesHeading: (v) => `NOVIDADES DA VERSÃO v${v}`,
    updatePreserveHint: 'Celulares pareados, política LAN_ONLY do PIN e caminho do Nuvio.exe são mantidos.',
    updateFallbackNote: 'Melhorias de estabilidade e ajustes gerais para o Nuvio DeskRemote Companion.',
    updateLaterBtn: 'Mais tarde',
    updateNowBtn: 'Atualizar agora',
    updateCancelBtn: 'Cancelar',
    updateInstallBtn: 'Instalar e reiniciar',
    updateCloseBtn: 'Fechar',
    updateRetryBtn: 'Tentar novamente',
    updateDownloadingSub: 'Baixando instalador do Windows (.exe)…',
    updateReadySub: 'Pronto para instalar • O Companion executará o instalador e reiniciará.',
    updateErrorNetwork:
      'Não foi possível baixar o pacote do GitHub Releases. Verifique sua conexão e tente novamente.',
    updateErrorIntegrity:
      'A verificação SHA-256 falhou e o arquivo foi descartado. Tente novamente.',
    nativeWin: 'Windows Nativo',
    brandSub: 'Controle HTPC + Trackpad + Tela na rede local',
    restartServerTitle: 'Reiniciar servidor',
    hideTrayTitle: 'Minimizar para a bandeja',
    pairingHeading: 'PAREAMENTO RÁPIDO (QR + PIN)',
    generatingQr: 'Gerando QR…',
    pinLabel: 'PIN DE 6 DÍGITOS (LAN)',
    pairingHint: 'Escaneie o QR Code no app Android ou digite este PIN no mesmo Wi-Fi.',
    refreshPinBtn: '↻ Gerar novo código (120s)',
    servicesHeading: 'STATUS DOS SERVIÇOS E REDE',
    companionServer: 'Servidor Companion',
    nuvioBridge: 'Ponte Nuvio HTPC',
    openNuvioBtn: 'Abrir Nuvio',
    localNetwork: 'Rede Local',
    devicesHeading: 'DISPOSITIVOS PAREADOS',
    emptyDevices: 'Nenhum celular pareado ainda. Escaneie o QR Code ao lado para começar.',
    pcControlsHeading: 'CONTROLES DO COMPUTADOR',
    nuvioExeLabel: 'CAMINHO DO NUVIO HTPC',
    browseBtn: 'Procurar…',
    autostartTitle: 'Iniciar com o Windows',
    autostartSub: 'Executa silenciosamente na bandeja ao entrar no Windows',
    tunnelTitle: 'Acesso Remoto (fora de casa)',
    copyUrlBtn: 'Copiar URL',
    copiedBtn: 'Copiado',
    lanOnlyNote:
      'Segurança: PIN de 6 dígitos e novo pareamento continuam restritos à rede local (LAN_ONLY). ' +
      'Apenas dispositivos já pareados no Wi-Fi podem conectar via túnel.',
    bridgeWarningHtml:
      'Nuvio HTPC não está aberto ou aguarda vínculo em <strong>Ajustes &gt; Controle pelo celular</strong>.',
    headerOnline: 'Servidor Ativo',
    headerStarting: 'Iniciando servidor…',
    headerRestarting: 'Reiniciando…',
    companionActive: (port) => `Ativo • Porta ${port}`,
    companionWaiting: 'Aguardando porta 8787',
    bridgeConnected: 'Conectada • Porta 47321',
    bridgePinRequired: 'Aguardando PIN',
    bridgeUnlinked: 'Sem vínculo',
    lanIp: (ip) => `IP Wi-Fi: ${ip || 'não detectado'}`,
    expiresIn: (mmss) => `Expira em ${mmss} (120s)`,
    deviceCount: (n) => `${n} ${n === 1 ? 'celular' : 'celulares'}`,
    androidDevice: 'Dispositivo Android',
    recently: 'recentemente',
    lastSeen: (ts) => `Último acesso: ${ts}`,
    revoke: 'Revogar',
    qrReady: 'QR pronto',
    exeConfigured: 'Configurado',
    exeAutodetected: 'Autodetectado',
    exeNotFound: 'Não encontrado',
    exeBrowseHint: 'Clique em Procurar… para apontar o Nuvio.exe',
    tunnelOnSub: 'Ligado • Túnel Cloudflare ativo (PIN continua LAN_ONLY)',
    tunnelOffSub: 'Desligado por padrão • Conexão restrita à rede Wi-Fi local',
    tunnelGenerating: 'Gerando URL do túnel Cloudflare…',
    locale: 'pt-BR',
  },
};

function detectInitialLang() {
  try {
    const saved = localStorage.getItem(LANG_STORAGE_KEY);
    if (saved === 'en' || saved === 'pt-BR') return saved;
  } catch {
    // ignore storage errors
  }
  const nav = (navigator.language || 'en').toLowerCase();
  return nav.startsWith('pt') ? 'pt-BR' : 'en';
}

let currentLang = detectInitialLang();
let lastStatus = null;
let lastConfig = null;
let lastDevices = [];
let lastLanIp = '';

function t() {
  return I18N[currentLang] || I18N.en;
}

const els = {
  btnLangEn: document.getElementById('btnLangEn'),
  btnLangPt: document.getElementById('btnLangPt'),
  headerStatusBadge: document.getElementById('headerStatusBadge'),
  headerStatusLabel: document.getElementById('headerStatusLabel'),
  pinCountdownBadge: document.getElementById('pinCountdownBadge'),
  qrBox: document.getElementById('qrBox'),
  pinCodeValue: document.getElementById('pinCodeValue'),
  pinProgressBar: document.getElementById('pinProgressBar'),
  btnRefreshPin: document.getElementById('btnRefreshPin'),
  companionLed: document.getElementById('companionLed'),
  companionBadge: document.getElementById('companionBadge'),
  bridgeLed: document.getElementById('bridgeLed'),
  bridgeBadge: document.getElementById('bridgeBadge'),
  bridgeWarningBox: document.getElementById('bridgeWarningBox'),
  bridgeWarningText: document.getElementById('bridgeWarningText'),
  btnOpenNuvio: document.getElementById('btnOpenNuvio'),
  lanIpLabel: document.getElementById('lanIpLabel'),
  devicesCountBadge: document.getElementById('devicesCountBadge'),
  devicesList: document.getElementById('devicesList'),
  nuvioExeStatusBadge: document.getElementById('nuvioExeStatusBadge'),
  nuvioExePathText: document.getElementById('nuvioExePathText'),
  btnBrowseNuvio: document.getElementById('btnBrowseNuvio'),
  toggleAutostart: document.getElementById('toggleAutostart'),
  toggleTunnel: document.getElementById('toggleTunnel'),
  tunnelSubtitle: document.getElementById('tunnelSubtitle'),
  tunnelActiveBox: document.getElementById('tunnelActiveBox'),
  tunnelUrlText: document.getElementById('tunnelUrlText'),
  btnCopyTunnel: document.getElementById('btnCopyTunnel'),
  btnRestartHeader: document.getElementById('btnRestartHeader'),
  btnHideTray: document.getElementById('btnHideTray'),
  pairingCard: document.getElementById('pairingCard'),
  btnCheckUpdate: document.getElementById('btnCheckUpdate'),
  updateDot: document.getElementById('updateDot'),
  headerVersionLabel: document.getElementById('headerVersionLabel'),
  updateModalBackdrop: document.getElementById('updateModalBackdrop'),
  updateModalKicker: document.getElementById('updateModalKicker'),
  updateModalTitle: document.getElementById('updateModalTitle'),
  updateVersionBadge: document.getElementById('updateVersionBadge'),
  updateViewAvailable: document.getElementById('updateViewAvailable'),
  updateAvailableSubtitle: document.getElementById('updateAvailableSubtitle'),
  updateNotesHeading: document.getElementById('updateNotesHeading'),
  updatePkgBadge: document.getElementById('updatePkgBadge'),
  updateNotesList: document.getElementById('updateNotesList'),
  updatePreserveHint: document.getElementById('updatePreserveHint'),
  btnUpdateLater: document.getElementById('btnUpdateLater'),
  btnUpdateNow: document.getElementById('btnUpdateNow'),
  updateViewProgress: document.getElementById('updateViewProgress'),
  updateAssetFileLabel: document.getElementById('updateAssetFileLabel'),
  updateProgressLabel: document.getElementById('updateProgressLabel'),
  updateProgressBar: document.getElementById('updateProgressBar'),
  updateProgressStatus: document.getElementById('updateProgressStatus'),
  updateShaBadge: document.getElementById('updateShaBadge'),
  btnUpdateCancel: document.getElementById('btnUpdateCancel'),
  btnUpdateInstall: document.getElementById('btnUpdateInstall'),
  updateViewError: document.getElementById('updateViewError'),
  updateErrorText: document.getElementById('updateErrorText'),
  btnUpdateCloseErr: document.getElementById('btnUpdateCloseErr'),
  btnUpdateRetry: document.getElementById('btnUpdateRetry'),
};

let installedVersion = '0.5.1';
let updateState = 'hidden'; // 'hidden' | 'available' | 'downloading' | 'ready' | 'error'
let updateInfo = null;
let updateProgress = { downloaded: 0, total: 0, percent: 0 };
let updateShaVerified = false;
let updateErrorKind = 'network';

let lastQrPayload = '';
let pinSecondsLeft = 0;

function applyStaticTranslations() {
  const dict = t();
  document.documentElement.lang = currentLang;
  els.btnLangEn.classList.toggle('active', currentLang === 'en');
  els.btnLangPt.classList.toggle('active', currentLang === 'pt-BR');

  document.querySelectorAll('[data-i18n]').forEach((node) => {
    const key = node.getAttribute('data-i18n');
    if (key && typeof dict[key] === 'string') {
      node.textContent = dict[key];
    }
  });
  document.querySelectorAll('[data-i18n-title]').forEach((node) => {
    const key = node.getAttribute('data-i18n-title');
    if (key && typeof dict[key] === 'string') {
      node.setAttribute('title', dict[key]);
      node.setAttribute('aria-label', dict[key]);
    }
  });
  if (els.bridgeWarningText) {
    els.bridgeWarningText.innerHTML = dict.bridgeWarningHtml;
  }
  updateCountdownUi();
  renderDevices(lastDevices);
  if (lastStatus && lastConfig) {
    renderStatusAndConfig(lastStatus, lastConfig);
  } else {
    els.lanIpLabel.textContent = dict.lanIp(lastLanIp);
  }
  renderUpdateModal();
}

function setLanguage(lang) {
  if (lang !== 'en' && lang !== 'pt-BR') return;
  currentLang = lang;
  try {
    localStorage.setItem(LANG_STORAGE_KEY, lang);
  } catch {
    // ignore storage errors
  }
  applyStaticTranslations();
}

async function companionApi(method, path, body = null) {
  if (!invoke) {
    const res = await fetch(`http://127.0.0.1:8787${path}`, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    });
    return res.json();
  }
  const raw = await invoke('companion_request', {
    method,
    path,
    body: body ? JSON.stringify(body) : null,
  });
  return JSON.parse(raw);
}

function formatPin(pin) {
  if (!pin || pin.length !== 6) return '--- ---';
  return `${pin.slice(0, 3)} ${pin.slice(3)}`;
}

function formatSeconds(sec) {
  const s = Math.max(0, Math.floor(sec));
  const mm = String(Math.floor(s / 60)).padStart(2, '0');
  const ss = String(s % 60).padStart(2, '0');
  return `${mm}:${ss}`;
}

function renderDevices(devices) {
  const dict = t();
  const list = Array.isArray(devices) ? devices : [];
  lastDevices = list;
  els.devicesCountBadge.textContent = dict.deviceCount(list.length);
  els.devicesList.innerHTML = '';

  if (list.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'opaque-cell empty-cell';
    empty.textContent = dict.emptyDevices;
    els.devicesList.appendChild(empty);
    return;
  }

  for (const dev of list) {
    const row = document.createElement('div');
    row.className = 'opaque-cell device-row';

    const meta = document.createElement('div');
    meta.className = 'device-meta';

    const name = document.createElement('span');
    name.className = 'row-title';
    name.textContent = dev.deviceName || dict.androidDevice;

    const sub = document.createElement('span');
    sub.className = 'helper-text';
    const seen = dev.lastSeenAt ? new Date(dev.lastSeenAt).toLocaleString(dict.locale) : dict.recently;
    sub.textContent = dict.lastSeen(seen);

    meta.appendChild(name);
    meta.appendChild(sub);

    const revokeBtn = document.createElement('button');
    revokeBtn.className = 'btn-danger btn-sm';
    revokeBtn.textContent = dict.revoke;
    revokeBtn.addEventListener('click', async () => {
      revokeBtn.disabled = true;
      try {
        await companionApi('POST', '/api/v1/devices/revoke', { device_id: dev.deviceId });
        await refreshPairInfo(false);
      } finally {
        revokeBtn.disabled = false;
      }
    });

    row.appendChild(meta);
    row.appendChild(revokeBtn);
    els.devicesList.appendChild(row);
  }
}

async function renderQr(payload) {
  if (!payload || payload === lastQrPayload) return;
  lastQrPayload = payload;
  if (invoke) {
    try {
      const svg = await invoke('render_qr_svg', { payload });
      els.qrBox.innerHTML = svg;
      return;
    } catch (err) {
      console.error('QR render failed:', err);
    }
  }
  els.qrBox.textContent = t().qrReady;
}

async function refreshPairInfo(forceRefresh = false) {
  try {
    const data = await companionApi('GET', `/api/v1/local/pair-info${forceRefresh ? '?refresh=1' : ''}`);
    els.pinCodeValue.textContent = formatPin(data.pin);
    pinSecondsLeft = Number(data.expires_in_seconds ?? 120);
    updateCountdownUi();
    await renderQr(data.qr_payload);
    lastLanIp = data.lan_ip || '';
    els.lanIpLabel.textContent = t().lanIp(lastLanIp);
    renderDevices(data.paired_devices);
  } catch {
    els.pinCodeValue.textContent = '--- ---';
  }
}

function updateCountdownUi() {
  const clamped = Math.max(0, Math.min(120, pinSecondsLeft));
  els.pinCountdownBadge.textContent = t().expiresIn(formatSeconds(clamped));
  els.pinProgressBar.style.width = `${Math.round((clamped / 120) * 100)}%`;
}

function renderStatusAndConfig(status, cfg) {
  const dict = t();
  els.headerStatusBadge.className = 'chip chip-online';
  els.headerStatusLabel.textContent = dict.headerOnline;
  els.companionLed.className = 'led led-online';
  els.companionBadge.className = 'chip chip-online';
  els.companionBadge.textContent = dict.companionActive(status.ws_port || 8787);
  if (status.ip) {
    lastLanIp = status.ip;
    els.lanIpLabel.textContent = dict.lanIp(status.ip);
  }

  const bridgeStatus = status.nuvio_bridge?.status || 'disconnected';
  if (bridgeStatus === 'connected') {
    els.bridgeLed.className = 'led led-online';
    els.bridgeBadge.className = 'chip chip-online';
    els.bridgeBadge.textContent = dict.bridgeConnected;
    els.bridgeWarningBox.classList.add('hidden');
  } else {
    els.bridgeLed.className = 'led led-warn';
    els.bridgeBadge.className = 'chip chip-warn';
    els.bridgeBadge.textContent =
      bridgeStatus === 'pin_required' ? dict.bridgePinRequired : dict.bridgeUnlinked;
    els.bridgeWarningBox.classList.remove('hidden');
  }

  const resolvedExe = cfg.nuvio_exe_path || cfg.detected_nuvio_exe_path;
  if (resolvedExe) {
    els.nuvioExeStatusBadge.className = 'chip chip-online';
    els.nuvioExeStatusBadge.textContent = cfg.nuvio_exe_path ? dict.exeConfigured : dict.exeAutodetected;
    els.nuvioExePathText.textContent = resolvedExe;
  } else {
    els.nuvioExeStatusBadge.className = 'chip chip-warn';
    els.nuvioExeStatusBadge.textContent = dict.exeNotFound;
    els.nuvioExePathText.textContent = dict.exeBrowseHint;
  }

  const tunnelOn = Boolean(cfg.tunnel_enabled);
  els.toggleTunnel.checked = tunnelOn;
  if (tunnelOn) {
    els.tunnelSubtitle.textContent = dict.tunnelOnSub;
    els.tunnelActiveBox.classList.remove('hidden');
    els.tunnelUrlText.textContent = cfg.tunnel_url || dict.tunnelGenerating;
    els.btnCopyTunnel.disabled = !cfg.tunnel_url;
  } else {
    els.tunnelSubtitle.textContent = dict.tunnelOffSub;
    els.tunnelActiveBox.classList.add('hidden');
  }
}

async function refreshStatusAndConfig() {
  try {
    const [status, cfg] = await Promise.all([
      companionApi('GET', '/api/v1/status'),
      companionApi('GET', '/api/v1/local/config'),
    ]);
    lastStatus = status;
    lastConfig = cfg;
    renderStatusAndConfig(status, cfg);
  } catch {
    const dict = t();
    els.headerStatusBadge.className = 'chip chip-warn';
    els.headerStatusLabel.textContent = dict.headerStarting;
    els.companionLed.className = 'led led-warn';
    els.companionBadge.className = 'chip chip-warn';
    els.companionBadge.textContent = dict.companionWaiting;
  }
}

async function initAutostartToggle() {
  if (!invoke) return;
  try {
    els.toggleAutostart.checked = Boolean(await invoke('get_autostart'));
  } catch {
    els.toggleAutostart.checked = false;
  }
}

els.btnLangEn.addEventListener('click', () => setLanguage('en'));
els.btnLangPt.addEventListener('click', () => setLanguage('pt-BR'));
els.btnRefreshPin.addEventListener('click', () => refreshPairInfo(true));

els.btnOpenNuvio.addEventListener('click', async () => {
  try {
    await companionApi('POST', '/api/v1/local/open-nuvio', {});
  } catch {
    // ignore
  }
});

els.btnBrowseNuvio.addEventListener('click', async () => {
  if (!invoke) return;
  const picked = await invoke('pick_nuvio_exe');
  if (picked) {
    await companionApi('POST', '/api/v1/local/config', { nuvio_exe_path: picked });
    await refreshStatusAndConfig();
  }
});

els.toggleAutostart.addEventListener('change', async () => {
  if (!invoke) return;
  try {
    await invoke('set_autostart', { enabled: els.toggleAutostart.checked });
  } catch {
    els.toggleAutostart.checked = !els.toggleAutostart.checked;
  }
});

els.toggleTunnel.addEventListener('change', async () => {
  const enabled = els.toggleTunnel.checked;
  els.toggleTunnel.disabled = true;
  try {
    await companionApi('POST', '/api/v1/local/config', { tunnel_enabled: enabled });
    await refreshStatusAndConfig();
  } finally {
    els.toggleTunnel.disabled = false;
  }
});

els.btnCopyTunnel.addEventListener('click', async () => {
  const url = els.tunnelUrlText.textContent?.trim();
  if (!url || !url.startsWith('http')) return;
  try {
    await navigator.clipboard.writeText(url);
    els.btnCopyTunnel.textContent = t().copiedBtn;
    setTimeout(() => {
      els.btnCopyTunnel.textContent = t().copyUrlBtn;
    }, 1800);
  } catch {
    // ignore
  }
});

els.btnRestartHeader.addEventListener('click', async () => {
  if (!invoke) return;
  els.headerStatusLabel.textContent = t().headerRestarting;
  await invoke('restart_companion');
  setTimeout(() => {
    void refreshStatusAndConfig();
    void refreshPairInfo(false);
  }, 1500);
});

els.btnHideTray.addEventListener('click', async () => {
  if (invoke) await invoke('hide_to_tray');
});

function formatBytesLocalized(bytes) {
  const safe = Math.max(0, Number(bytes) || 0);
  const locale = t().locale;
  if (safe >= 1024 * 1024) {
    return `${(safe / (1024 * 1024)).toLocaleString(locale, {
      minimumFractionDigits: 1,
      maximumFractionDigits: 1,
    })} MB`;
  }
  return `${Math.round(safe / 1024).toLocaleString(locale)} KB`;
}

function shouldShowAutoUpdatePrompt(version) {
  try {
    const raw = localStorage.getItem(DISMISSED_UPDATE_KEY);
    if (!raw) return true;
    const saved = JSON.parse(raw);
    if (!saved || saved.version !== version) return true;
    return Date.now() - Number(saved.at || 0) >= UPDATE_DISMISS_COOLDOWN_MS;
  } catch {
    return true;
  }
}

function saveDismissedUpdate(version) {
  try {
    localStorage.setItem(DISMISSED_UPDATE_KEY, JSON.stringify({ version, at: Date.now() }));
  } catch {
    // ignore storage errors
  }
}

function getLocalizedReleaseNotes(info) {
  if (!info) return [];
  const en = Array.isArray(info.notes_en) ? info.notes_en : [];
  const pt = Array.isArray(info.notes_pt_br) ? info.notes_pt_br : [];
  if (currentLang === 'pt-BR') {
    return pt.length > 0 ? pt : en;
  }
  return en.length > 0 ? en : pt;
}

function renderUpdateModal() {
  if (!els.updateModalBackdrop) return;
  const dict = t();
  if (updateState === 'hidden' || !updateInfo) {
    els.updateModalBackdrop.classList.add('hidden');
    return;
  }

  els.updateModalBackdrop.classList.remove('hidden');
  els.updateViewAvailable.classList.toggle('hidden', updateState !== 'available');
  els.updateViewProgress.classList.toggle(
    'hidden',
    updateState !== 'downloading' && updateState !== 'ready',
  );
  els.updateViewError.classList.toggle('hidden', updateState !== 'error');

  els.updateModalKicker.classList.toggle('is-error', updateState === 'error');
  if (updateState === 'available') {
    els.updateModalKicker.textContent = dict.updateKickerAvailable;
    els.updateModalTitle.textContent = dict.updateTitleAvailable;
    els.updateVersionBadge.textContent = `v${updateInfo.current_version} → v${updateInfo.latest_version}`;
    els.updateAvailableSubtitle.textContent = dict.updateSubtitleAvailable;
    els.updateNotesHeading.textContent = dict.updateNotesHeading(updateInfo.latest_version);
    const sizeLabel = updateInfo.asset_size > 0 ? ` • ${formatBytesLocalized(updateInfo.asset_size)}` : '';
    els.updatePkgBadge.textContent = `Windows .exe${sizeLabel}`;
    els.updatePreserveHint.textContent = dict.updatePreserveHint;
    els.btnUpdateLater.textContent = dict.updateLaterBtn;
    els.btnUpdateNow.textContent = dict.updateNowBtn;

    const notes = getLocalizedReleaseNotes(updateInfo);
    const displayNotes = notes.length > 0 ? notes : [dict.updateFallbackNote];
    els.updateNotesList.innerHTML = '';
    for (const item of displayNotes) {
      const li = document.createElement('li');
      li.textContent = `• ${item}`;
      els.updateNotesList.appendChild(li);
    }
  } else if (updateState === 'downloading' || updateState === 'ready') {
    const isReady = updateState === 'ready';
    els.updateModalKicker.textContent = dict.updateKickerProgress;
    els.updateModalTitle.textContent = isReady ? dict.updateTitleReady : dict.updateTitleDownloading;
    els.updateVersionBadge.textContent = `v${updateInfo.latest_version}`;
    els.updateAssetFileLabel.textContent = updateInfo.asset_name || 'Nuvio-DeskRemote-Setup.exe';
    const pct = isReady ? 100 : updateProgress.percent;
    const total = updateProgress.total || updateInfo.asset_size || 0;
    const dl = isReady ? total : updateProgress.downloaded;
    els.updateProgressLabel.textContent =
      total > 0 ? `${pct}% • ${formatBytesLocalized(dl)} / ${formatBytesLocalized(total)}` : `${pct}%`;
    els.updateProgressBar.style.width = `${Math.max(isReady ? 100 : 2, pct)}%`;
    els.updateProgressStatus.textContent = isReady ? dict.updateReadySub : dict.updateDownloadingSub;
    els.updateShaBadge.classList.toggle('hidden', !isReady || !updateShaVerified);
    els.btnUpdateCancel.textContent = dict.updateCancelBtn;
    els.btnUpdateInstall.textContent = dict.updateInstallBtn;
    els.btnUpdateInstall.classList.toggle('hidden', !isReady);
  } else if (updateState === 'error') {
    els.updateModalKicker.textContent = dict.updateKickerError;
    els.updateModalTitle.textContent = dict.updateTitleError;
    els.updateVersionBadge.textContent = 'GitHub API';
    els.updateErrorText.textContent =
      updateErrorKind === 'integrity' ? dict.updateErrorIntegrity : dict.updateErrorNetwork;
    els.btnUpdateCloseErr.textContent = dict.updateCloseBtn;
    els.btnUpdateRetry.textContent = dict.updateRetryBtn;
  }
}

async function checkForUpdates(manual = false) {
  if (!invoke || updateState === 'downloading') return;
  if (manual && els.headerVersionLabel) {
    els.headerVersionLabel.textContent = t().checkingUpdates;
  }
  try {
    const res = await invoke('check_github_update');
    if (res?.current_version) {
      installedVersion = res.current_version;
    }
    if (res?.update_available) {
      updateInfo = res;
      els.updateDot?.classList.remove('hidden');
      if (els.headerVersionLabel) {
        els.headerVersionLabel.textContent = `v${res.latest_version}`;
      }
      if (manual || shouldShowAutoUpdatePrompt(res.latest_version)) {
        updateState = 'available';
        renderUpdateModal();
      }
    } else if (manual && els.headerVersionLabel) {
      els.headerVersionLabel.textContent = t().upToDateToast(installedVersion);
      setTimeout(() => {
        if (els.headerVersionLabel && !updateInfo) {
          els.headerVersionLabel.textContent = `v${installedVersion}`;
        }
      }, 3000);
    } else if (els.headerVersionLabel) {
      els.headerVersionLabel.textContent = `v${installedVersion}`;
    }
  } catch {
    if (manual && els.headerVersionLabel) {
      els.headerVersionLabel.textContent = `v${installedVersion}`;
    }
  }
}

async function startUpdateDownload() {
  if (!invoke || !updateInfo) return;
  if (els.btnUpdateInstall) els.btnUpdateInstall.disabled = false;
  updateShaVerified = false;
  updateProgress = { downloaded: 0, total: updateInfo.asset_size || 0, percent: 0 };
  updateState = 'downloading';
  renderUpdateModal();

  try {
    const res = await invoke('download_github_update');
    updateShaVerified = Boolean(res?.sha256_verified);
    updateState = 'ready';
    renderUpdateModal();
  } catch (err) {
    const msg = String(err || '');
    if (msg.toLowerCase().includes('cancelled')) {
      updateState = 'available';
      renderUpdateModal();
      return;
    }
    updateErrorKind = msg.toLowerCase().includes('sha-256') ? 'integrity' : 'network';
    updateState = 'error';
    renderUpdateModal();
  }
}

async function initAppVersionAndAutoUpdate() {
  if (!invoke) return;
  try {
    const ver = await invoke('get_app_version');
    if (ver) {
      installedVersion = ver;
      if (els.headerVersionLabel) els.headerVersionLabel.textContent = `v${ver}`;
    }
  } catch {
    // keep default label
  }
  setTimeout(() => {
    void checkForUpdates(false);
  }, 2500);
}

els.btnCheckUpdate?.addEventListener('click', () => {
  if (updateInfo && updateState === 'hidden') {
    updateState = 'available';
    renderUpdateModal();
  } else {
    void checkForUpdates(true);
  }
});

els.btnUpdateLater?.addEventListener('click', () => {
  if (updateInfo?.latest_version) saveDismissedUpdate(updateInfo.latest_version);
  updateState = 'hidden';
  renderUpdateModal();
});

els.btnUpdateNow?.addEventListener('click', () => {
  void startUpdateDownload();
});

els.btnUpdateCancel?.addEventListener('click', async () => {
  if (updateState === 'downloading' && invoke) {
    try {
      await invoke('cancel_github_update');
    } catch {
      // ignore
    }
  } else {
    updateState = 'hidden';
    renderUpdateModal();
  }
});

els.btnUpdateInstall?.addEventListener('click', async () => {
  if (!invoke) return;
  els.btnUpdateInstall.disabled = true;
  try {
    await invoke('install_downloaded_update');
  } catch (err) {
    els.btnUpdateInstall.disabled = false;
    const msg = String(err || '').toLowerCase();
    if (msg.includes('cancelled') || msg.includes('elevation')) {
      return;
    }
    updateErrorKind = msg.includes('sha-256') ? 'integrity' : 'network';
    updateState = 'error';
    renderUpdateModal();
  }
});

els.btnUpdateCloseErr?.addEventListener('click', () => {
  updateState = 'hidden';
  renderUpdateModal();
});

els.btnUpdateRetry?.addEventListener('click', () => {
  void startUpdateDownload();
});

els.updateModalBackdrop?.addEventListener('click', (ev) => {
  if (ev.target !== els.updateModalBackdrop) return;
  if (updateState === 'available') {
    if (updateInfo?.latest_version) saveDismissedUpdate(updateInfo.latest_version);
    updateState = 'hidden';
    renderUpdateModal();
  } else if (updateState === 'error') {
    updateState = 'hidden';
    renderUpdateModal();
  }
});

if (listen) {
  void listen('focus-pairing', () => {
    void refreshPairInfo(true);
    els.pairingCard.scrollIntoView({ behavior: 'smooth', block: 'center' });
  });
  void listen('update-download-progress', (event) => {
    if (updateState !== 'downloading' || !event?.payload) return;
    updateProgress = {
      downloaded: Number(event.payload.downloaded || 0),
      total: Number(event.payload.total || 0),
      percent: Number(event.payload.percent || 0),
    };
    renderUpdateModal();
  });
}

applyStaticTranslations();
void initAutostartToggle();
void initAppVersionAndAutoUpdate();
void refreshStatusAndConfig();
void refreshPairInfo(false);

setInterval(() => {
  if (document.hidden) return;
  if (pinSecondsLeft > 0) {
    pinSecondsLeft -= 1;
    updateCountdownUi();
    if (pinSecondsLeft === 0) {
      void refreshPairInfo(true);
    }
  }
}, 1000);

setInterval(() => {
  if (document.hidden) return;
  void refreshStatusAndConfig();
  void refreshPairInfo(false);
}, 5000);

document.addEventListener('visibilitychange', () => {
  if (!document.hidden) {
    void refreshStatusAndConfig();
    void refreshPairInfo(false);
  }
});
