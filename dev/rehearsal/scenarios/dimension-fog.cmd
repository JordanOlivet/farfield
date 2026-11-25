# Thick fog and the LODs (ADR-033, ADR-034, ADR-179), about 8 min on a dev client.
#   .\gradlew.bat :neoforge:runClient -PquickPlay=farfield-test -PagentWindow=minimized
#   dev/rehearsal/run-commands.sh neoforge/run dev/rehearsal/scenarios/dimension-fog.cmd
# The save's dragon must be alive (a new copy of dev/'s test world): its boss bar
# shows within 192 blocks of the End's centre and brings the game's thick fog.
# Read lod.drawn of each `state` in outbox.jsonl, in this order:
#   1. End, boss bar shown, thick fog distance vanilla: about 100 (2 before ADR-179)
#   2. the same with the thick fog distance off: about 100
#   3. End, out of the boss bar's range, vanilla: about 100
#   4. Nether, vanilla: 2 (the dimension's own fog, ADR-033)
#   5. Nether, off: about 100
# and `Boss fog left out while the LODs are drawn` in the game log; the first
# three captures show the far islands, and no boss fog under the boss bar.
# The run changes the save (the End and the Nether are visited) and the thick fog
# distance: copy the save and config/farfield-client.properties first, put them back after.
command gamemode spectator
wait stable 240 3
command execute in minecraft:the_end run tp @s 0 110 0 0 20
command farfield fog thick vanilla
wait seconds 45
wait stable 120 3
state
screenshot end-boss-vanilla hud
command farfield fog thick off
wait seconds 20
wait stable 300 3
state
screenshot end-boss-off hud
command farfield fog thick vanilla
tp 420 110 0 90 15
wait seconds 25
wait stable 300 3
state
screenshot end-far-vanilla hud
command execute in minecraft:the_nether run tp @s 0 90 0 0 10
wait seconds 30
wait stable 120 3
tp 220 90 0 90 10
wait seconds 20
wait stable 120 3
state
screenshot nether-vanilla hud
command farfield fog thick off
wait seconds 15
wait stable 240 3
state
screenshot nether-off hud
command farfield fog thick vanilla
quit
