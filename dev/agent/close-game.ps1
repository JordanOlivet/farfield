# Closes the game JVMs a checkout started (dev/agent/run-commands.sh): java
# processes whose command line names the checkout's folder and that started
# after -Since (Unix seconds), Gradle's own JVMs left out. The window is asked
# to close first, so the game can save its options; what is left after 15 s is
# killed.
param([Parameter(Mandatory)][string]$Root, [Parameter(Mandatory)][long]$Since)

$folder = $Root -replace '/', '\'
$startTime = [DateTimeOffset]::FromUnixTimeSeconds($Since).LocalDateTime
$games = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" | Where-Object {
    $_.CommandLine -and ($_.CommandLine -replace '/', '\').IndexOf($folder, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
    $_.CommandLine -notmatch 'GradleDaemon|GradleWrapperMain|GradleWorkerMain' -and $_.CreationDate -ge $startTime
})
if ($games.Count -eq 0) {
    "no game JVM of $folder to close"
    exit 0
}
foreach ($g in $games) {
    $p = Get-Process -Id $g.ProcessId -ErrorAction SilentlyContinue
    if ($p -and $p.MainWindowHandle -ne 0) { [void]$p.CloseMainWindow() }
}
$deadline = (Get-Date).AddSeconds(15)
while ((Get-Date) -lt $deadline -and @($games | Where-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue }).Count -gt 0) {
    Start-Sleep -Milliseconds 500
}
foreach ($g in $games) {
    if (Get-Process -Id $g.ProcessId -ErrorAction SilentlyContinue) {
        Stop-Process -Id $g.ProcessId -Force
        "killed game JVM $($g.ProcessId)"
    } else {
        "closed game JVM $($g.ProcessId)"
    }
}
