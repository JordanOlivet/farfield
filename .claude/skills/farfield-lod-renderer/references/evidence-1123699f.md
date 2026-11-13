/** What the selection forgets before it runs: null for everything, else {drained tiles, drained coarse}. */
    private final ConcurrentLinkedQueue<long[][]> existenceOps = new ConcurrentLinkedQueue<>();
...
-        previewLayer.setDetail(detail);
...
-        previewLayer.setOutsideView(viewed, outsideDetail);
+    /** Applies what the render thread queued to forget. Selection thread, before a selection. */