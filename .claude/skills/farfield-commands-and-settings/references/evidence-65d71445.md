// The draw distance, then the fog shown or not over it (ADR-140): "off" held the LODs to 4,096 blocks.
// A file from before ADR-140: off hid the fog and held the LODs to 4,096 blocks.
Files.writeString(file, "fog_distance=off\n", StandardCharsets.UTF_8);
RenderConfig config = RenderConfig.read(file);
assertFalse(config.fog());
assertEquals(RenderConfig.DEFAULT_DRAW_DISTANCE, config.fogDistance());