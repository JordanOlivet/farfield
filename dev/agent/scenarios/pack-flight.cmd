# The pack run (ADR-097, ADR-098): six 256-block jumps east over terrain never
# generated, a capture after each, about 5 min with the pack's start.
#   .\gradlew.bat :neoforge:runPackClient -PquickPlay=farfield-test -PfreshWorld -PagentWindow=minimized
#   dev/agent/run-commands.sh neoforge/runs/pack dev/agent/scenarios/pack-flight.cmd 1200
# Check: no "Connector locator error" in logs/debug.log, Sodium and Iris in
# the mod list, both waits "settled", eight PNGs in farfield-agent/shots.
state
wait stable 300 3
state
screenshot p-spawn
tp 304 170 32 -90 25
wait seconds 4
screenshot p-east-1
tp 560 170 32 -90 25
wait seconds 4
screenshot p-east-2
tp 816 170 32 -90 25
wait seconds 4
screenshot p-east-3
tp 1072 170 32 -90 25
wait seconds 4
screenshot p-east-4
tp 1328 170 32 -90 25
wait seconds 4
screenshot p-east-5
tp 1584 170 32 -90 25
wait seconds 4
screenshot p-east-6
wait stable 180 3
screenshot p-east-settled
state
quit
