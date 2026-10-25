-        if (level > 0 && distance < detail * size) {
+        double split = inView != null && outsideDetail < detail && !inView.test(key) ? outsideDetail : detail;
+        if (level > 0 && distance < split * size) {
Commit f3e2a0f: "fix(render): spyglass detail in view only; no section kept empty for want of room; waterlogged blocks are fluid". ADR-063 text: "the narrow projection raised the detail from 4.6 to 62.6 section sizes, every section around split to level 0 (126,760 resident, 1,023 of 1,024 MB of quads), and a mesh that found no room was kept as an empty resident".