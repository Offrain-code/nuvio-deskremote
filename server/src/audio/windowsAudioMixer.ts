import { execFile } from 'node:child_process';
import { AudioAppSession, AudioMixerState, AudioOutputDevice } from '../protocol';

const PS_MIXER_CS = `
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;

public static class NuvioCoreAudio {
  [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
  private class MMDeviceEnumerator {}

  [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  private interface IMMDeviceEnumerator {
    int EnumAudioEndpoints(int dataFlow, int dwStateMask, out IMMDeviceCollection ppDevices);
    int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice ppEndpoint);
  }

  [Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  private interface IMMDeviceCollection {
    int GetCount(out int pcDevices);
    int Item(int nDevice, out IMMDevice ppDevice);
  }

  [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  private interface IMMDevice {
    int Activate(ref Guid iid, int dwClsCtx, IntPtr pActivationParams, [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);
    int OpenPropertyStore(int stgmAccess, out IPropertyStore ppProperties);
    int GetId([MarshalAs(UnmanagedType.LPWStr)] out string ppstrId);
  }

  [StructLayout(LayoutKind.Sequential)]
  private struct PROPERTYKEY { public Guid fmtid; public int pid; }

  [StructLayout(LayoutKind.Explicit)]
  private struct PROPVARIANT {
    [FieldOffset(0)] public short vt;
    [FieldOffset(8)] public IntPtr pwszVal;
  }

  [Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  private interface IPropertyStore {
    int GetCount(out int cProps);
    int GetAt(int iProp, out PROPERTYKEY pkey);
    int GetValue(ref PROPERTYKEY key, out PROPVARIANT pv);
  }

  [Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  private interface IAudioSessionManager2 {
    int GetAudioSessionControl(IntPtr g, int f, IntPtr c);
    int GetSimpleAudioVolume(IntPtr g, int f, IntPtr v);
    int GetSessionEnumerator(out IAudioSessionEnumerator SessionEnum);
  }

  [Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  private interface IAudioSessionEnumerator {
    int GetCount(out int SessionCount);
    int GetSession(int SessionCount, out IAudioSessionControl Session);
  }

  [Guid("F4B1A599-7266-4319-A8CA-E70ACB11E8CD"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  private interface IAudioSessionControl {
    int GetState(out int state);
    int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string pRetVal);
  }

  [Guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  private interface IAudioSessionControl2 {
    int GetState(out int state);
    int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string pRetVal);
    int SetDisplayName([MarshalAs(UnmanagedType.LPWStr)] string Value, ref Guid EventContext);
    int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string pRetVal);
    int SetIconPath([MarshalAs(UnmanagedType.LPWStr)] string Value, ref Guid EventContext);
    int GetGroupingParam(out Guid pRetVal);
    int SetGroupingParam(ref Guid Override, ref Guid EventContext);
    int RegisterAudioSessionNotification(IntPtr NewNotifications);
    int UnregisterAudioSessionNotification(IntPtr NewNotifications);
    int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string pRetVal);
    int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string pRetVal);
    int GetProcessId(out uint pRetVal);
    int IsSystemSoundsSession();
  }

  [Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  private interface ISimpleAudioVolume {
    int SetMasterVolume(float fLevel, ref Guid EventContext);
    int GetMasterVolume(out float pfLevel);
    int SetMute(bool bMute, ref Guid EventContext);
    int GetMute(out bool pbMute);
  }

  [ComImport, Guid("870af99c-171d-4f9e-af0d-e63df40c2bc9")]
  private class PolicyConfigClient {}

  [Guid("f8679f50-850a-41cf-9c72-430f290290c8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  private interface IPolicyConfig {
    int GetMixFormat(string a, IntPtr b);
    int GetDeviceFormat(string a, int b, IntPtr c);
    int ResetDeviceFormat(string a);
    int SetDeviceFormat(string a, IntPtr b, IntPtr c);
    int GetProcessingPeriod(string a, int b, IntPtr c, IntPtr d);
    int SetProcessingPeriod(string a, IntPtr b);
    int GetShareMode(string a, IntPtr b);
    int SetShareMode(string a, IntPtr b);
    int GetPropertyValue(string a, IntPtr b, IntPtr c);
    int SetPropertyValue(string a, IntPtr b, IntPtr c);
    int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string wszDeviceId, int role);
  }

  private static string ReadFriendlyName(IMMDevice dev) {
    try {
      IPropertyStore store;
      dev.OpenPropertyStore(0, out store);
      var key = new PROPERTYKEY { fmtid = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"), pid = 14 };
      PROPVARIANT pv;
      store.GetValue(ref key, out pv);
      if (pv.vt == 31 && pv.pwszVal != IntPtr.Zero) {
        return Marshal.PtrToStringUni(pv.pwszVal) ?? "Audio Device";
      }
    } catch {}
    return "Audio Device";
  }

  public static object GetState() {
    var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumerator());
    string defaultId = "";
    IMMDevice defDev = null;
    try {
      enumerator.GetDefaultAudioEndpoint(0, 0, out defDev);
      if (defDev != null) defDev.GetId(out defaultId);
    } catch {}

    var outputs = new List<Dictionary<string, object>>();
    IMMDeviceCollection col;
    enumerator.EnumAudioEndpoints(0, 1, out col);
    int devCount;
    col.GetCount(out devCount);
    for (int i = 0; i < devCount; i++) {
      IMMDevice d;
      col.Item(i, out d);
      string id;
      d.GetId(out id);
      outputs.Add(new Dictionary<string, object> {
        { "id", id ?? "" },
        { "name", ReadFriendlyName(d) },
        { "isDefault", string.Equals(id, defaultId, StringComparison.OrdinalIgnoreCase) }
      });
    }

    var sessions = new List<Dictionary<string, object>>();
    var seenPids = new HashSet<uint>();
    if (defDev != null) {
      object mgrObj;
      var iid = typeof(IAudioSessionManager2).GUID;
      defDev.Activate(ref iid, 23, IntPtr.Zero, out mgrObj);
      var mgr = mgrObj as IAudioSessionManager2;
      if (mgr != null) {
        IAudioSessionEnumerator sEnum;
        mgr.GetSessionEnumerator(out sEnum);
        int sCount;
        sEnum.GetCount(out sCount);
        for (int i = 0; i < sCount; i++) {
          IAudioSessionControl ctrl;
          sEnum.GetSession(i, out ctrl);
          var ctrl2 = ctrl as IAudioSessionControl2;
          var vol = ctrl as ISimpleAudioVolume;
          if (ctrl2 == null || vol == null) continue;
          uint pid;
          ctrl2.GetProcessId(out pid);
          if (pid == 0 || !seenPids.Add(pid)) continue;
          string name = "App " + pid;
          try { name = Process.GetProcessById((int)pid).ProcessName; } catch {}
          float level;
          bool muted;
          vol.GetMasterVolume(out level);
          vol.GetMute(out muted);
          sessions.Add(new Dictionary<string, object> {
            { "pid", (int)pid },
            { "name", name },
            { "volume", (int)Math.Round(level * 100f) },
            { "muted", muted }
          });
        }
      }
    }
    return new Dictionary<string, object> { { "outputs", outputs }, { "sessions", sessions } };
  }

  public static bool SetAppVolume(int targetPid, int volumePct, int muteMode) {
    var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumerator());
    IMMDevice defDev;
    enumerator.GetDefaultAudioEndpoint(0, 0, out defDev);
    if (defDev == null) return false;
    object mgrObj;
    var iid = typeof(IAudioSessionManager2).GUID;
    defDev.Activate(ref iid, 23, IntPtr.Zero, out mgrObj);
    var mgr = mgrObj as IAudioSessionManager2;
    if (mgr == null) return false;
    IAudioSessionEnumerator sEnum;
    mgr.GetSessionEnumerator(out sEnum);
    int sCount;
    sEnum.GetCount(out sCount);
    bool changed = false;
    var ctx = Guid.Empty;
    for (int i = 0; i < sCount; i++) {
      IAudioSessionControl ctrl;
      sEnum.GetSession(i, out ctrl);
      var ctrl2 = ctrl as IAudioSessionControl2;
      var vol = ctrl as ISimpleAudioVolume;
      if (ctrl2 == null || vol == null) continue;
      uint pid;
      ctrl2.GetProcessId(out pid);
      if ((int)pid != targetPid) continue;
      if (volumePct >= 0) {
        float clamped = Math.Max(0f, Math.Min(1f, volumePct / 100f));
        vol.SetMasterVolume(clamped, ref ctx);
        changed = true;
      }
      if (muteMode == 0 || muteMode == 1) {
        vol.SetMute(muteMode == 1, ref ctx);
        changed = true;
      }
    }
    return changed;
  }

  public static bool SetDefaultOutput(string deviceId) {
    try {
      var policy = (IPolicyConfig)(new PolicyConfigClient());
      policy.SetDefaultEndpoint(deviceId, 0);
      policy.SetDefaultEndpoint(deviceId, 1);
      policy.SetDefaultEndpoint(deviceId, 2);
      return true;
    } catch {
      return false;
    }
  }
}
`;

/** Validates a Windows CoreAudio endpoint ID to prevent shell/script injection. */
export function isValidAudioDeviceId(id: unknown): id is string {
  if (typeof id !== 'string') return false;
  const trimmed = id.trim();
  return trimmed.length > 0 && trimmed.length <= 128 && /^\{[0-9a-fA-F.\-{}]+\}$/.test(trimmed);
}

function runPowerShell(script: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const encoded = Buffer.from(script, 'utf16le').toString('base64');
    execFile(
      'powershell.exe',
      ['-NoProfile', '-NonInteractive', '-EncodedCommand', encoded],
      { timeout: 6000, windowsHide: true, maxBuffer: 512 * 1024 },
      (err, stdout) => {
        if (err) return reject(err);
        resolve(stdout.trim());
      },
    );
  });
}

export async function getWindowsAudioMixerState(): Promise<AudioMixerState> {
  if (process.platform !== 'win32') {
    return { outputs: [], sessions: [] };
  }
  try {
    const script = `Add-Type -TypeDefinition @'\n${PS_MIXER_CS}\n'@\n[NuvioCoreAudio]::GetState() | ConvertTo-Json -Depth 4 -Compress`;
    const raw = await runPowerShell(script);
    if (!raw) return { outputs: [], sessions: [] };
    const parsed = JSON.parse(raw) as { outputs?: unknown; sessions?: unknown };
    const outputsArr = Array.isArray(parsed.outputs)
      ? parsed.outputs
      : parsed.outputs
        ? [parsed.outputs]
        : [];
    const sessionsArr = Array.isArray(parsed.sessions)
      ? parsed.sessions
      : parsed.sessions
        ? [parsed.sessions]
        : [];
    const outputs: AudioOutputDevice[] = outputsArr
      .map((o) => o as Partial<AudioOutputDevice>)
      .filter((o) => typeof o.id === 'string' && typeof o.name === 'string')
      .map((o) => ({ id: o.id!, name: o.name!, isDefault: Boolean(o.isDefault) }));
    const sessions: AudioAppSession[] = sessionsArr
      .map((s) => s as Partial<AudioAppSession>)
      .filter((s) => typeof s.pid === 'number' && typeof s.name === 'string')
      .map((s) => ({
        pid: Math.trunc(s.pid!),
        name: s.name!,
        volume: Math.max(0, Math.min(100, Math.round(Number(s.volume ?? 100)))),
        muted: Boolean(s.muted),
      }));
    return { outputs, sessions };
  } catch {
    return { outputs: [], sessions: [] };
  }
}

export async function setWindowsAppVolume(pid: number, volume?: number, muted?: boolean): Promise<boolean> {
  if (process.platform !== 'win32' || !Number.isInteger(pid) || pid <= 0) return false;
  const volArg = typeof volume === 'number' && Number.isFinite(volume)
    ? Math.max(0, Math.min(100, Math.round(volume)))
    : -1;
  const muteArg = typeof muted === 'boolean' ? (muted ? 1 : 0) : -1;
  try {
    const script = `Add-Type -TypeDefinition @'\n${PS_MIXER_CS}\n'@\n[NuvioCoreAudio]::SetAppVolume(${pid}, ${volArg}, ${muteArg})`;
    const out = await runPowerShell(script);
    return out.toLowerCase().includes('true');
  } catch {
    return false;
  }
}

export async function setWindowsDefaultAudioOutput(deviceId: string): Promise<boolean> {
  if (process.platform !== 'win32' || !isValidAudioDeviceId(deviceId)) return false;
  try {
    const script = `Add-Type -TypeDefinition @'\n${PS_MIXER_CS}\n'@\n[NuvioCoreAudio]::SetDefaultOutput('${deviceId}')`;
    const out = await runPowerShell(script);
    return out.toLowerCase().includes('true');
  } catch {
    return false;
  }
}
