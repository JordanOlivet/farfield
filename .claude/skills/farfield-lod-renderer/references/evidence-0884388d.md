git add common/src/main/java/io/lakio/farfield/compat/GlCompat.java common/src/main/java/io/lakio/farfield/client/render/LodRenderer.java common/src/main/java/io/lakio/farfield/client/render/GlProgram.java common/src/main/resources/assets/farfield/shaders/lod.frag common/src/main/resources/assets/farfield/shaders/dh_bridge.frag && git commit -q -F - <<'EOF'
fix: the LODs' depth under 26.2's reversed depth (ADR-171)

26.2 draws with reversed depth: clip control zero to one, depth cleared to
0, tests greater or equal. The LODs kept 26.1's convention, so they failed
their less-or-equal test against a depth of 0, and clearing the depth to 1
after them hid every later draw: the frame kept only the sky (Fabric and
NeoForge 26.2, render on against render off at one spot).

From 26.2 the LODs' draws put back the clip control of 26.1 and clear the
target's depth to 1 first, restore the game's clip control after, and the
depth goes back to the game's clear value. The shaders' "the game drew
here" test compares with that value instead of 1.