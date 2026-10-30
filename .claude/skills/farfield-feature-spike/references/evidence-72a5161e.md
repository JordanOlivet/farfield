[30oct.2026 12:51:55.946] [farfield-surface-spike/ERROR] [Farfield/]: Surface spike failed
java.lang.NullPointerException: Cannot invoke "net.minecraft.world.level.chunk.ChunkAccess.getHeight(net.minecraft.world.level.levelgen.Heightmap$Types, int, int)" because "real" is null
	at TRANSFORMER/farfield@0.1.0/io.lakio.farfield.server.SurfaceSpike.run(SurfaceSpike.java:111) ~[%23975!/:?]
--- 
minecraft:plains at 4752, 75, 3360: 8x8 chunks from chunk 293 206
tops right, columns: surface rules 99.8 % (its one column a cell), guess 17.1 %; cells: surface rules 97.3 %, guess 18.4 %
surface rules 1.79 ms a stand-in chunk; real chunks to surface 100.2 ms each