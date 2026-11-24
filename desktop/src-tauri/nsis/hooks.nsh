; Automatic lifecycle and Windows Firewall hooks during Nuvio DeskRemote Companion install/uninstall
; Runs elevated via bundle.windows.nsis.installMode = "perMachine"
!macro NSIS_HOOK_PREINSTALL
  ; Stop the Companion app and all bundled child helpers before overwriting files, and restore PC speaker mute state
  nsExec::ExecToLog 'powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -Command "$$root = \"$INSTDIR\resources\server\"; Get-Process -Name nuvio-deskremote-companion -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue; Get-Process -Name node,ffmpeg,cloudflared,nuvio-audio-capture -ErrorAction SilentlyContinue | Where-Object { $$_.Path -and $$_.Path.StartsWith($$root, [System.StringComparison]::OrdinalIgnoreCase) } | Stop-Process -Force -ErrorAction SilentlyContinue; $$unm = Join-Path $$root \"bin\nuvio-audio-capture.exe\"; if (Test-Path $$unm) { Start-Process -FilePath $$unm -ArgumentList \"--unmute\" -WindowStyle Hidden -Wait -ErrorAction SilentlyContinue }"'
  Sleep 600
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ; Stop the Companion app and all bundled child helpers cleanly before removing files, and restore PC speaker mute state
  nsExec::ExecToLog 'powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -Command "$$root = \"$INSTDIR\resources\server\"; Get-Process -Name nuvio-deskremote-companion -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue; Get-Process -Name node,ffmpeg,cloudflared,nuvio-audio-capture -ErrorAction SilentlyContinue | Where-Object { $$_.Path -and $$_.Path.StartsWith($$root, [System.StringComparison]::OrdinalIgnoreCase) } | Stop-Process -Force -ErrorAction SilentlyContinue; $$unm = Join-Path $$root \"bin\nuvio-audio-capture.exe\"; if (Test-Path $$unm) { Start-Process -FilePath $$unm -ArgumentList \"--unmute\" -WindowStyle Hidden -Wait -ErrorAction SilentlyContinue }"'
  Sleep 600
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ; Clean any stale, legacy broad, or auto-blocked rules before adding scoped LAN rules
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name=all program="$INSTDIR\resources\server\node.exe"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (Server TCP)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (Server UDP)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (WS 8787)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (Pair 8765)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (UDP Discovery 8766)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (UDP Mouse 8768)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (WebRTC UDP 60000-60100)"'

  ; Allow only the bundled Node.js Companion server on its explicit LAN ports, restricted to Private networks and local subnet
  nsExec::ExecToLog 'netsh advfirewall firewall add rule name="Nuvio DeskRemote Companion (WS 8787)" dir=in action=allow program="$INSTDIR\resources\server\node.exe" protocol=TCP localport=8787 remoteip=localsubnet profile=private'
  nsExec::ExecToLog 'netsh advfirewall firewall add rule name="Nuvio DeskRemote Companion (Pair 8765)" dir=in action=allow program="$INSTDIR\resources\server\node.exe" protocol=TCP localport=8765 remoteip=localsubnet profile=private'
  nsExec::ExecToLog 'netsh advfirewall firewall add rule name="Nuvio DeskRemote Companion (UDP Discovery 8766)" dir=in action=allow program="$INSTDIR\resources\server\node.exe" protocol=UDP localport=8766 remoteip=localsubnet profile=private'
  nsExec::ExecToLog 'netsh advfirewall firewall add rule name="Nuvio DeskRemote Companion (UDP Mouse 8768)" dir=in action=allow program="$INSTDIR\resources\server\node.exe" protocol=UDP localport=8768 remoteip=localsubnet profile=private'
  nsExec::ExecToLog 'netsh advfirewall firewall add rule name="Nuvio DeskRemote Companion (WebRTC UDP 60000-60100)" dir=in action=allow program="$INSTDIR\resources\server\node.exe" protocol=UDP localport=60000-60100 remoteip=localsubnet profile=private'
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  ; Remove Windows autostart entry and Firewall rules on uninstall
  nsExec::ExecToLog 'reg.exe delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "NuvioDeskRemoteCompanion" /f'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (Server TCP)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (Server UDP)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (WS 8787)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (Pair 8765)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (UDP Discovery 8766)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (UDP Mouse 8768)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (WebRTC UDP 60000-60100)"'
!macroend
