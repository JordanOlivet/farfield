# Flights and the frame probe (ADR-099), about 90 s: the 400-tick flight
# dev/agent/flights/east.flight replayed with the probe on and recorded back,
# then replayed fast with the probe off.
#   .\gradlew.bat :neoforge:runClient -PquickPlay=farfield-test -PfreshWorld -PagentWindow=minimized
#   dev/agent/run-commands.sh neoforge/run dev/agent/scenarios/replay-probe.cmd
# (run-commands.sh copies dev/agent/flights/ into the run dir's farfield/flights/.)
# Check: replay "ticks":400; farfield/flights/echo.flight holds east's ticks
# after the 40 ticks of the start teleport, 0.0 apart; farfield/frames/<session>/
# has first-background|lods|end.png the right way up and events.jsonl; the
# "Frame probe: ... in 10 s" lines give the render thread cost; compare the
# fps of the two state lines after the replays (probe on, then off).
wait stable 240 3
state
frames on
record start echo
replay east every 100
record stop
state
frames off
replay east speed 4
state
quit
