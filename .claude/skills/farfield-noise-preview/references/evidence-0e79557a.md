PROBE samples level 0, 0 to 600 blocks: 3049 with ground, 67612 void, tops p1/p10/p50/p90/p99 28/37/45/57/61 max 63, thick pcts 1/2/10/36/47
PROBE samples level 0, 1000 to 1500 blocks: 45083 with ground, 49440 void, tops p1/p10/p50/p90/p99 29/37/47/58/62 max 236, thick pcts 1/3/12/39/46
echo "execute in minecraft:the_end run forceload add 1104 -128 1343 111" >> "$CMD"
echo "farfield pregen start 1540 0 520 minecraft:the_end" >> "$CMD"
sed -i 's/^preview_spawn_radius=.*/preview_spawn_radius=256/' $RUN/config/farfield-server.properties