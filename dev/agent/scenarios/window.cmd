# The client draws while out of the way (ADR-096), about 60 s.
#   .\gradlew.bat :neoforge:runClient -PquickPlay=farfield-test -PfreshWorld -PagentWindow=minimized
#   dev/agent/run-commands.sh neoforge/run dev/agent/scenarios/window.cmd
# Beside it, to prove the focus never moves:
#   powershell -File dev/agent/focus-watch.ps1 -Out <log> -StopFile <file>
# Check: the shots taken minimized (w-south) and in the background
# (w-east-background) show the new view after each look; the focus log never
# says gameHasFocus=True; the game log says "Agent window: minimized at launch".
state
wait stable 120 3
screenshot w-spawn
tp 48 170 32 0 25
wait stable 120 3
screenshot w-north
look 180 25
wait seconds 1
screenshot w-south
state
window background
wait seconds 2
look 90 25
wait seconds 1
screenshot w-east-background
state
quit
