/** Called before any record is written, to persist what records refer to (the key files). */
    @FunctionalInterface
    public interface BeforeWrite {
        void run() throws IOException;
    }

Also from the edit: `PreviewStore(Path dir, io.lakio.farfield.core.storage.SectionStore.BeforeWrite beforeWrite)` and `beforeWrite.run();` at the top of `public void write(int tileX, int tileZ, PreviewTile tile)`, with `preview = new PreviewStore(dir.resolve("preview"), () -> { blockStateKeys.persist(); biomeKeys.persist(); });` in LodWorld.