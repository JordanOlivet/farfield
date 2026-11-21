# Create: Sky compatibility test, 2026-11-19

Version tested: Create: Sky 0.1.0 (1.21.1, NeoForge), beside Farfield on the NeoForge dev client.

## Observed
- Farfield and Create: Sky load together, no crash.
- Create: Sky sizes its clouds and cloud shadows from the vanilla render distance.
- At vanilla distance 8 (128 blocks), a white haze covers everything beyond, with no cloud shadows. Clouds stop at the same place with or without Farfield.
- At vanilla distance 32, large soft cloud shadows fall on vanilla terrain and water, and a haze band sits at the horizon under the clouds. The cloud field still stops short of the horizon.
- Farfield at distance 8: LOD terrain reaches the horizon, but there are no cloud shadows and a sky gap between the last clouds and the LOD horizon.
- A vertical line in the middle of a high view comes from Create: Sky alone, not from Farfield.

## Limits
- Clouds move with time. A 65 s load at distance 32 showed different clouds, so only pairs from one session compare.
- The Farfield run at distance 32 was not made; its expected result (shadows stay on vanilla terrain) is read from the code, not observed.

## Reading of the other mod (reconstructed, not confirmed by its author)
- It reads only Distant Horizons' distant terrain for LOD depth. Without a Farfield bridge, the LOD depth it needs is zero.
- Its cloud volumes seem tuned for Distant Horizons range (a few thousand blocks); a Farfield distance of 65000 or more may need a cap on the announced distance.
