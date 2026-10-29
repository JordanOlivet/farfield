# Agent inbox smoke test (ADR-095), about 80 s on a dev client.
#   .\gradlew.bat :neoforge:runClient -PquickPlay=farfield-test -PfreshWorld -PagentWindow=minimized
#   dev/agent/run-commands.sh neoforge/run dev/agent/scenarios/inbox-smoke.cmd
# Check: every line answered in outbox.jsonl, both waits "settled", the two
# refused lines at the end ok=false, four PNGs in farfield-agent/shots (far
# view drawn, HUD shown only on south-hud, level colours on levels), and
# "LOD level colors on" then "off" as [CHAT] lines in the game log.
state
wait stable 240 3
state
screenshot spawn
tp 48 170 32 0 25
wait stable 240 3
screenshot north
look 180 25
wait seconds 1
command gamemode creative
wait seconds 1
screenshot south-hud hud
command gamemode spectator
command /farfieldclient debug levels
wait seconds 2
screenshot levels
command farfieldclient debug levels
# Refused on purpose: relative coordinates and an unknown verb.
tp ^ ^ ^3
fly
state
quit
