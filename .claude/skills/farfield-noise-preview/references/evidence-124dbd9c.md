+    /** An LOD import runs or waits: tasks keep to their near area until it ends. */
+    void setImporting(boolean now) {
+        if (now != importing) {
+            importing = now;
...
+    static final int NEAR_WHILE_IMPORTING = 1024;