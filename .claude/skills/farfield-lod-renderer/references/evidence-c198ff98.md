+        int dropped = PreviewLayer.isPreview(key) ? PreviewLayer.maskMaterial(w) : -1;
+                if (Quad.material(q) == dropped) {
+                    continue;
+                }