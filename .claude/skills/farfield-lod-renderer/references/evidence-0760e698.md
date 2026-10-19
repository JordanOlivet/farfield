Les lignes droites correspondent aux bords des sections LOD (occlusion ambiante qui ignore le voxel voisin d'une autre section), et les courbes marquent la limite où l'occlusion s'arrête au-delà du niveau 1. Je vais corriger ça en lisant l'occlusion à travers les sections voisines et en l'appliquant à tous les niveaux.

113:    private static final int OCCUPANCY_INTS = LodSection.SIZE * LodSection.SIZE;
115:    private static final int AO_MAX_LEVEL = 1;
...
    return ((occupancy[v_slot * 1024u + uint(v.y * 32 + v.z)] >> uint(v.x)) & 1u) != 0u;

Hypothèse confirmée : les différences avant/après tombent pile sur les bords de sections (les lignes sur la montagne), et au loin là où l'occlusion manquait.