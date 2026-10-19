d=np.clip(np.abs(a-b)*6,0,255).astype(np.uint8)
Image.fromarray(d[850:1700,2450:3400]).resize((1140,1020)).save(r'$S/mtn_diff.png')
print(round(float(np.abs(a-b).mean()),2))
→ 0.7

Hypothèse confirmée : les différences avant/après tombent pile sur les bords de sections (les lignes sur la montagne), et au loin là où l'occlusion manquait.