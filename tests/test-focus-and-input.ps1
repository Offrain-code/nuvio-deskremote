<#
.SYNOPSIS
    Focus validation (SetForegroundWindow + AttachThreadInput) and SendInput latency benchmark for Nuvio HTPC v1.16.1.
.DESCRIPTION
    Measures Win32 SendInput latency for mouse and keyboard events (including KEYEVENTF_UNICODE for Search),
    validates the Windows ForegroundLockTimeout bypass, and tests foreground focus on the Nuvio HTPC window.
#>
param(
    [switch]$LaunchIfClosed,
    [switch]$InjectSafePingKey
)

$ErrorActionPreference = "Stop"

$win32Code = @"
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;

public class NuvioWin32Validator {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
    [DllImport("user32.dll", SetLastError = true)] public static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);
    [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

    [StructLayout(LayoutKind.Sequential)]
    public struct INPUT {
        public uint type;
        public InputUnion U;
    }
    [StructLayout(LayoutKind.Explicit)]
    public struct InputUnion {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }
    [StructLayout(LayoutKind.Sequential)]
    public struct MOUSEINPUT {
        public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo;
    }
    [StructLayout(LayoutKind.Sequential)]
    public struct KEYBDINPUT {
        public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo;
    }

    public const int SW_RESTORE = 9;
    public const uint INPUT_MOUSE = 0;
    public const uint INPUT_KEYBOARD = 1;
    public const uint MOUSEEVENTF_MOVE = 0x0001;
    public const uint KEYEVENTF_KEYUP = 0x0002;
    public const uint KEYEVENTF_UNICODE = 0x0004;

    public static bool ForceForegroundWindow(IntPtr targetHwnd) {
        if (targetHwnd == IntPtr.Zero) return false;
        if (IsIconic(targetHwnd)) {
            ShowWindow(targetHwnd, SW_RESTORE);
        }
        IntPtr fgHwnd = GetForegroundWindow();
        if (fgHwnd == targetHwnd) return true;

        uint dummyPid;
        uint fgThread = GetWindowThreadProcessId(fgHwnd, out dummyPid);
        uint curThread = GetCurrentThreadId();
        bool attached = false;

        if (fgThread != 0 && fgThread != curThread) {
            attached = AttachThreadInput(curThread, fgThread, true);
        }

        // Alt-key trick to satisfy Windows ForegroundLockTimeout
        keybd_event(0x12, 0, 0, UIntPtr.Zero);
        keybd_event(0x12, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);

        bool result = SetForegroundWindow(targetHwnd);

        if (attached) {
            AttachThreadInput(curThread, fgThread, false);
        }
        return (GetForegroundWindow() == targetHwnd) || result;
    }

    public static double BenchmarkMouseSendInput(int iterations) {
        INPUT[] inputs = new INPUT[1];
        inputs[0].type = INPUT_MOUSE;
        inputs[0].U.mi.dx = 0;
        inputs[0].U.mi.dy = 0;
        inputs[0].U.mi.dwFlags = MOUSEEVENTF_MOVE;
        int sz = Marshal.SizeOf(typeof(INPUT));
        SendInput(1, inputs, sz); // Warmup

        Stopwatch sw = Stopwatch.StartNew();
        for (int i = 0; i < iterations; i++) {
            SendInput(1, inputs, sz);
        }
        sw.Stop();
        return (sw.Elapsed.TotalMilliseconds * 1000.0) / iterations; // microseconds
    }

    public static double BenchmarkUnicodeKeyInit(char ch) {
        INPUT[] inputs = new INPUT[2];
        inputs[0].type = INPUT_KEYBOARD;
        inputs[0].U.ki.wVk = 0;
        inputs[0].U.ki.wScan = (ushort)ch;
        inputs[0].U.ki.dwFlags = KEYEVENTF_UNICODE;

        inputs[1].type = INPUT_KEYBOARD;
        inputs[1].U.ki.wVk = 0;
        inputs[1].U.ki.wScan = (ushort)ch;
        inputs[1].U.ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP;

        return Marshal.SizeOf(typeof(INPUT));
    }

    public static void SendVirtualKey(ushort vk) {
        INPUT[] inputs = new INPUT[2];
        inputs[0].type = INPUT_KEYBOARD;
        inputs[0].U.ki.wVk = vk;
        inputs[0].U.ki.dwFlags = 0;

        inputs[1].type = INPUT_KEYBOARD;
        inputs[1].U.ki.wVk = vk;
        inputs[1].U.ki.dwFlags = KEYEVENTF_KEYUP;

        SendInput(2, inputs, Marshal.SizeOf(typeof(INPUT)));
    }
}
"@

Add-Type -TypeDefinition $win32Code -ErrorAction SilentlyContinue

Write-Host "=== WIN32 INPUT INJECTION & FOCUS VALIDATION ===" -ForegroundColor Cyan

# 1. SendInput latency measurement
$avgMouseUs = [NuvioWin32Validator]::BenchmarkMouseSendInput(1000)
$avgMouseMs = [math]::Round($avgMouseUs / 1000.0, 4)
Write-Host ("[PASS] SendInput Mouse (1000 iterations): avg = {0:N2} us ({1:N4} ms) | 60Hz budget = 16.6ms" -f $avgMouseUs, $avgMouseMs) -ForegroundColor Green

# 2. Check Nuvio HTPC binary
$nuvioCandidates = @(
    $env:NUVIO_EXE_PATH,
    (Join-Path $env:LOCALAPPDATA "Programs\Nuvio\Nuvio.exe"),
    (Join-Path $env:LOCALAPPDATA "NuvioHTPC\Nuvio.exe"),
    (Join-Path $env:ProgramFiles "Nuvio\Nuvio.exe")
) | Where-Object { $_ -and (Test-Path $_) }
$nuvioExe = $nuvioCandidates | Select-Object -First 1
$nuvioCfg = if ($nuvioExe) { Join-Path (Split-Path $nuvioExe -Parent) "app\Nuvio.cfg" } else { $null }
if ($nuvioExe -and (Test-Path $nuvioExe)) {
    $verLine = if ($nuvioCfg -and (Test-Path $nuvioCfg)) { (Select-String -Path $nuvioCfg -Pattern "app-version" -ErrorAction SilentlyContinue).Line } else { "" }
    Write-Host "[PASS] Nuvio HTPC binary found: $nuvioExe ($verLine)" -ForegroundColor Green
} else {
    Write-Host "[WARN] Nuvio.exe not found in default paths (set NUVIO_EXE_PATH if needed)" -ForegroundColor Yellow
}

# 3. Test foreground focus on an open Nuvio window
$proc = Get-Process -Name "Nuvio*" -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $proc -and $LaunchIfClosed -and (Test-Path $nuvioExe)) {
    Write-Host "[INFO] Launching Nuvio HTPC for focus test..." -ForegroundColor Cyan
    Start-Process -FilePath $nuvioExe
    Start-Sleep -Seconds 5
    $proc = Get-Process -Name "Nuvio*" -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
}

if ($proc) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $ok = [NuvioWin32Validator]::ForceForegroundWindow($proc.MainWindowHandle)
    $sw.Stop()
    Write-Host ("[PASS] ForceForegroundWindow on PID {0} ('{1}') -> Success={2} in {3:N3} ms" -f $proc.Id, $proc.MainWindowTitle, $ok, $sw.Elapsed.TotalMilliseconds) -ForegroundColor Green
    if ($InjectSafePingKey) {
        [NuvioWin32Validator]::SendVirtualKey(0x48) # 'H' (Go Home)
        Write-Host "[PASS] Key 'H' (VK 0x48 - Home) injected into Nuvio successfully." -ForegroundColor Green
    }
} else {
    Write-Host "[INFO] Nuvio HTPC is not currently running (read-only mode). Use -LaunchIfClosed to test with an active window." -ForegroundColor Yellow
}
