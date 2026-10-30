# Bands at the generation front over land (ADR-100): the east flight, the
# frame probe on. With dev/agent/series.sh.
wait stable 300 3
command gamemode spectator
tp 48 170 32 -90 20
wait stable 240 3
screenshot b-start
frames on
replay east every 100
wait stable 240 3
state
frames off
screenshot b-end
quit
