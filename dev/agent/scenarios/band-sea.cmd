# Bands at the generation front (ADR-100), single player: the sea flight over
# a sea never generated, the frame probe on. With dev/agent/series.sh.
wait stable 300 3
command gamemode spectator
tp 1808 110 1700 0 20
wait stable 240 3
screenshot b-start
frames on
replay sea-1808 every 250
wait stable 240 3
state
frames off
screenshot b-end
quit
