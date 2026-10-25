+                    if (bottom >= height || water >= 0 && blocks < s) {
+                        // A ground voxel filled only partly lights the faces seen above its fill
+                        // (a neighbor's side): the water's light too, not full light.
---
message="org.opentest4j.AssertionFailedError: blocks 52..55 ==&gt; expected: &lt;7&gt; but was: &lt;15&gt;"