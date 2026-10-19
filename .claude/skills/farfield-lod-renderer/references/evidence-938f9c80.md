+            // Fluids also lose the last ninth of a block, in blocks, as on level 0:
+            // the sea surface lies at the same height on every level.
+            if (u_translucent) {
+                float height = uintBitsToFloat(colors[material * COLOR_STRIDE + HEIGHT_OFFSET]);
+                if (height < 1.0) {
+                    p.y -= (1.0 - height) / scale;
+                }
+            }
Verified: dark slit pixels in the edge band went from 3707 to 40 at the same view (Fabric dev client, open water).