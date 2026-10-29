# Proves an agent's run stays out of the user's way (ADR-096): every 250 ms,
# logs to -Out each change of the foreground window and of the game window
# (minimized or not, focused or not), until the -StopFile appears. Start it
# before the client, in the background; a line with gameHasFocus=True means
# the game took the focus. Window titles may name what the user has open:
# keep the log out of the repository.
#   powershell -NoProfile -ExecutionPolicy Bypass -File dev/agent/focus-watch.ps1 -Out <log> -StopFile <file>
param([Parameter(Mandatory)][string]$Out, [Parameter(Mandatory)][string]$StopFile)
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public static class FW {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  public static string Title(IntPtr h) { var s = new StringBuilder(256); GetWindowText(h, s, 256); return s.ToString(); }
}
"@
$last = ""
while (-not (Test-Path $StopFile)) {
  $fg = [FW]::GetForegroundWindow(); $p = 0; [void][FW]::GetWindowThreadProcessId($fg, [ref]$p)
  $fgName = (Get-Process -Id $p -ErrorAction SilentlyContinue).ProcessName
  $game = Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Select-Object -First 1
  $gs = if ($game) { "game='" + $game.MainWindowTitle + "' iconic=" + [FW]::IsIconic($game.MainWindowHandle) + " gameHasFocus=" + ($game.MainWindowHandle -eq $fg) } else { "game=none" }
  $line = "fg=$fgName '" + [FW]::Title($fg) + "' | $gs"
  if ($line -ne $last) { (Get-Date -Format "HH:mm:ss.fff") + " " + $line | Out-File -Append -Encoding ascii $Out; $last = $line }
  Start-Sleep -Milliseconds 250
}
