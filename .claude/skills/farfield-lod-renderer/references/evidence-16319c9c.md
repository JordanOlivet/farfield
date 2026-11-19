+                    // Faded in: the game's Chunk Fade time since it was first seen built (1.21.9 on).
+                    boolean faded = fade <= 0 || compiledAt[bit] == 0 || now - compiledAt[bit] >= fade;
-                    if (compiled && before) {
+                    if (compiled && before && faded) {
- **Reconnect**: since 1.21.9 a section the game builds fades in over the
  Chunk Fade time (0.75 s by default; Sodium 0.8 does it too), and the LODs
  gave way as soon as it was built: the sky showed through the fading
  terrain, white patches over whole areas after a reconnect.