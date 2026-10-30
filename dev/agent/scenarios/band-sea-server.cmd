# Bands at the generation front (ADR-100), a client joined to a dev server:
# the sea flight. A fixed wait first, the join's view never settles before
# the server streams. With dev/agent/stream-series.sh.
wait seconds 25
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
