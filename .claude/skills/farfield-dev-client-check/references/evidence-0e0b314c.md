$p = Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "Minecraft*" } | Select-Object -First 1
if (-not $p) { Write-Output "no Minecraft window"; exit 1 }
$h = $p.MainWindowHandle