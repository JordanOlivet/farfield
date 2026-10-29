./gradlew.bat :core:test --tests '*TileProbeTest' --no-daemon -i --rerun-tasks 2>&1 | grep -E "PROBE|FAILED|Exception" | head -20; rm -rf core/src/test/java/io/lakio/farfield/core/probe

Tool result: "    PROBE tile true\n    PROBE plants biomesoplenty:high_grass_plant 20,1 ... PROBE voxels minecraft:tall_grass 308\n    PROBE voxels minecraft:sunflower 14"