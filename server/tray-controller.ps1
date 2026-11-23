# Nuvio DeskRemote Server - System Tray Controller
# Runs 100% hidden in background (zero CMD windows) and shows a tray icon near the Windows clock.

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$ServerDir = $PSScriptRoot
$NodeCmd   = Get-Command node.exe -ErrorAction SilentlyContinue
$NodeExe   = if ($NodeCmd) { $NodeCmd.Source } else { Join-Path $env:ProgramFiles "nodejs\node.exe" }
$EntryJs   = Join-Path $ServerDir "dist\index.js"
$LogFile   = Join-Path $ServerDir "companion.log"
$ErrFile   = Join-Path $ServerDir "companion.err.log"

$mutexName = "Global\NuvioDeskRemoteTrayControllerMutex"
$createdNew = $false
$mutex = New-Object System.Threading.Mutex($true, $mutexName, [ref]$createdNew)

$script:ServerProc = $null

function Rotate-CompanionLog {
    param([string]$Path)
    if (Test-Path $Path) {
        $len = (Get-Item $Path -ErrorAction SilentlyContinue).Length
        if ($len -ge 2MB) {
            $old = "$Path.old"
            if (Test-Path $old) { Remove-Item $old -Force -ErrorAction SilentlyContinue }
            Move-Item $Path $old -Force -ErrorAction SilentlyContinue
        }
    }
}

function Get-ServerProcess {
    Get-CimInstance Win32_Process -Filter "Name='node.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*nuvio-remote*dist*index.js*" }
}

function Stop-ServerProcess {
    if ($script:ServerProc -and -not $script:ServerProc.HasExited) {
        Stop-Process -Id $script:ServerProc.Id -Force -ErrorAction SilentlyContinue
    }
    $script:ServerProc = $null
    $procs = Get-ServerProcess
    foreach ($p in $procs) {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

function Start-ServerProcess {
    Stop-ServerProcess
    Start-Sleep -Milliseconds 300
    Rotate-CompanionLog $LogFile
    Rotate-CompanionLog $ErrFile
    $script:ServerProc = Start-Process -FilePath $NodeExe -ArgumentList "`"$EntryJs`"" -WorkingDirectory $ServerDir -WindowStyle Hidden -RedirectStandardOutput $LogFile -RedirectStandardError $ErrFile -PassThru
}

if (-not $createdNew) {
    exit 0
}

# Draw a 32x32 Ocean Cyan (#00E5FF) tray icon on dark background (#0B0F17)
$bmp = New-Object System.Drawing.Bitmap(32, 32)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$bgBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 11, 15, 23))
$cyanPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 0, 229, 255), 2.5)
$cyanBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 0, 229, 255))
$g.FillEllipse($bgBrush, 1, 1, 29, 29)
$g.DrawEllipse($cyanPen, 3, 3, 25, 25)
$g.FillEllipse($cyanBrush, 11, 11, 9, 9)
$g.Dispose()
$bgBrush.Dispose()
$cyanPen.Dispose()
$cyanBrush.Dispose()
$iconHandle = $bmp.GetHicon()
$customIcon = [System.Drawing.Icon]::FromHandle($iconHandle).Clone()
$bmp.Dispose()

$existing = Get-ServerProcess | Select-Object -First 1
if ($existing) {
    $script:ServerProc = Get-Process -Id $existing.ProcessId -ErrorAction SilentlyContinue
}
if (-not $script:ServerProc -or $script:ServerProc.HasExited) {
    Start-ServerProcess
}

$notifyIcon = New-Object System.Windows.Forms.NotifyIcon
$notifyIcon.Icon = $customIcon
$notifyIcon.Text = "Nuvio DeskRemote Server - Active (Port 8787)"
$notifyIcon.Visible = $true

$menu = New-Object System.Windows.Forms.ContextMenuStrip

$statusItem = New-Object System.Windows.Forms.ToolStripMenuItem("Nuvio DeskRemote Server - Active (8787)")
$statusItem.Enabled = $false
$menu.Items.Add($statusItem) | Out-Null
$menu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator)) | Out-Null

$restartItem = New-Object System.Windows.Forms.ToolStripMenuItem("Restart Server")
$restartItem.Add_Click({
    Start-ServerProcess
    $notifyIcon.ShowBalloonTip(2000, "Nuvio DeskRemote", "Server restarted in background.", [System.Windows.Forms.ToolTipIcon]::Info)
})
$menu.Items.Add($restartItem) | Out-Null

$openNuvioItem = New-Object System.Windows.Forms.ToolStripMenuItem("Open Nuvio HTPC")
$openNuvioItem.Add_Click({
    $candidates = @(
        $env:NUVIO_EXE_PATH,
        (Join-Path $env:LOCALAPPDATA "Programs\Nuvio\Nuvio.exe"),
        (Join-Path $env:LOCALAPPDATA "NuvioHTPC\Nuvio.exe"),
        (Join-Path $env:ProgramFiles "Nuvio\Nuvio.exe"),
        (Join-Path $env:ProgramFiles "NuvioHTPC\Nuvio.exe")
    ) | Where-Object { $_ -and (Test-Path $_) }
    $nuvioExe = $candidates | Select-Object -First 1
    if ($nuvioExe) {
        Start-Process -FilePath $nuvioExe
    }
})
$menu.Items.Add($openNuvioItem) | Out-Null

$logItem = New-Object System.Windows.Forms.ToolStripMenuItem("View Server Log")
$logItem.Add_Click({
    if (Test-Path $LogFile) {
        Start-Process notepad.exe -ArgumentList "`"$LogFile`""
    }
})
$menu.Items.Add($logItem) | Out-Null

$menu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator)) | Out-Null

$exitItem = New-Object System.Windows.Forms.ToolStripMenuItem("Stop Server and Exit")
$exitItem.Add_Click({
    Stop-ServerProcess
    $notifyIcon.Visible = $false
    $notifyIcon.Dispose()
    [System.Windows.Forms.Application]::Exit()
})
$menu.Items.Add($exitItem) | Out-Null

$notifyIcon.ContextMenuStrip = $menu

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 10000
$timer.Add_Tick({
    if (-not $script:ServerProc -or $script:ServerProc.HasExited) {
        Start-ServerProcess
    }
})
$timer.Start()

[System.Windows.Forms.Application]::Run()
