-        // As Iris's own DH programs: the custom uniforms carry the common
-        // non-dynamic ones (vx* included); adding those too logged dozens
-        // of "Duplicate uniform" warnings.
-        CommonUniforms.addDynamicUniforms(uniforms, FogMode.PER_VERTEX);
+        // All of the common uniforms, then the pack's custom ones. Some names
+        // come twice ("Duplicate uniform" warnings, harmless); the dynamic
+        // ones alone, as Iris's DH programs, left the snippets' common
+        // uniforms unset: LODs dark or white under BSL, Complementary, MakeUp.
Le changement est annulé.