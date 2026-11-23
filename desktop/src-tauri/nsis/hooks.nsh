; Automatic Windows Firewall rules during Nuvio DeskRemote Companion install/uninstall
; Runs elevated via bundle.windows.nsis.installMode = "perMachine"
!macro NSIS_HOOK_POSTINSTALL
  ; Clean any stale or auto-blocked rules before adding fresh LAN rules
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name=all program="$INSTDIR\resources\server\node.exe"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (Server TCP)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (Server UDP)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (WS 8787)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (Pair 8765)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (UDP Discovery 8766)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (UDP Mouse 8768)"'

  ; Allow bundled Node.js Companion server + explicit LAN ports on Private networks
  nsExec::ExecToLog 'netsh advfirewall firewall add rule name="Nuvio DeskRemote Companion (Server TCP)" dir=in action=allow program="$INSTDIR\resources\server\node.exe" protocol=TCP profile=private'
  nsExec::ExecToLog 'netsh advfirewall firewall add rule name="Nuvio DeskRemote Companion (Server UDP)" dir=in action=allow program="$INSTDIR\resources\server\node.exe" protocol=UDP profile=private'
  nsExec::ExecToLog 'netsh advfirewall firewall add rule name="Nuvio DeskRemote Companion (WS 8787)" dir=in action=allow protocol=TCP localport=8787 profile=private'
  nsExec::ExecToLog 'netsh advfirewall firewall add rule name="Nuvio DeskRemote Companion (Pair 8765)" dir=in action=allow protocol=TCP localport=8765 profile=private'
  nsExec::ExecToLog 'netsh advfirewall firewall add rule name="Nuvio DeskRemote Companion (UDP Discovery 8766)" dir=in action=allow protocol=UDP localport=8766 profile=private'
  nsExec::ExecToLog 'netsh advfirewall firewall add rule name="Nuvio DeskRemote Companion (UDP Mouse 8768)" dir=in action=allow protocol=UDP localport=8768 profile=private'
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (Server TCP)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (Server UDP)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (WS 8787)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (Pair 8765)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (UDP Discovery 8766)"'
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="Nuvio DeskRemote Companion (UDP Mouse 8768)"'
!macroend
