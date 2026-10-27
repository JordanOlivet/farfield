v0: 1780 isolated bright pixels, 10 of the user colors (first 400 listed)
v_b1: 109 isolated bright pixels, 10 of the user colors (first 400 listed)
v_b2: 109 isolated bright pixels, 10 of the user colors (first 400 listed)
v_b0lin: 1750 isolated bright pixels, 10 of the user colors (first 400 listed)
v_lin: 9838 isolated bright pixels, 0 of the user colors (first 400 listed)
v_lin_b1: 79 isolated bright pixels, 0 of the user colors (first 400 listed)

Ni le biais de mip ni le filtre linéaire ne corrigent le scintillement (le second l'aggrave même en créant du moiré) : le problème ne vient donc pas du choix de mip. Je ferme ce run et annule ces deux changements.

## Texture sampling variants: none removes the squares

Temporary switches (`debug texbias`, `debug texfilter`, reverted, never
committed), one dev run with the user's yes, the same preview view: mip
bias 1 and 2 left the user-coloured specks at the same pixels with the
same colour (the far water already samples the coarsest mip, one texel
per block, so a bias changes nothing there); a linear minification
filter made it worse (moiré: it reads across the atlas's neighbor tiles).
So the specks are not a mip choice. Still established: they go with the
textures off (A/B in one session). Open hypothesis: at the coarsest mip a
block takes its tile's 1x1 average, alpha included, while the untextured
path takes the material's mean colour and alpha; a water alpha lower
with textures lets the floor (sand on ridges) show through per block.