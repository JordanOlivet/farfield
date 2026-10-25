User (last prompt): "C'est nikel, franchement on est presque bon pour une 0.2.0. Il reste un dernier truc, je t'ai fais un screenshots. En gros, les fond marins en preview ont comme des bandes blanches, chose que les LOD…"

Diff of the fix:
"+            // Its neighbors are built from the tiles: their solid layers for water faces, and
+            // their light for every face on a border (else a sea floor face there took full
+            // light, white bands through the preview's sea)."
"+                light[d] = BinaryGreedyMesher.borderLight(layer, d, null);"