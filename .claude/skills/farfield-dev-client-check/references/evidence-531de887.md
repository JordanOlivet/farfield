wait_for "Bench ocean step 2200" 300 $start || { close_game; cp "$L" "$S/run-$name.log"; continue; }

var found = level.findClosestBiome3d(b -> b.is(Biomes.COLD_OCEAN) || b.is(Biomes.OCEAN)
                    || b.is(Biomes.LUKEWARM_OCEAN), player.blockPosition(), 6400, 32, 64);

python - <<'EOF' ... s.replace(a,b) with b = "        Bench.tick(server);\n        LodServer s = instance;" ... EOF