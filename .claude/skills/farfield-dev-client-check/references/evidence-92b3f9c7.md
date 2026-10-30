rm -rf $S/fresh-s1 && mkdir -p $S/fresh-s1 && cp dev/server/world/level.dat $S/fresh-s1/
JAVA_TOOL_OPTIONS="-Dfarfield.frames.dumps=400 -Dfarfield.handOver=$sw" ./gradlew.bat :fabric:runClient \
mode on exit 0 streaming-lines 1 | 20261030-025843 BANDS 0 | lods flashes 37 far 2 | end flashes 34 | columns 31 | hidden column-frames 42318, expired 0
mode off exit 0 streaming-lines 1 | 20261030-025843 BANDS 6 | lods flashes 42 far 12 | end flashes 43 | columns 31 | 20261030-025843 LIGHTER 6 darker 0 unwritten 0 | hidden column-frames 0, expired 0