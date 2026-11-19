- **Reconnect**: since 1.21.9 a section the game builds fades in over the
  Chunk Fade time (0.75 s by default; Sodium 0.8 does it too), and the LODs
  gave way as soon as it was built: the sky showed through the fading
  terrain, white patches over whole areas after a reconnect. The LODs now
  give way once the section has been built for the Chunk Fade time
  (Sodium) or is fully opaque (vanilla, `getVisibility` at 1).
Pictures: "les captures de mon run sont arrivées avant que le monde ne s'affiche."