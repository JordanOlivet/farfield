Pour le versionnement des tuiles, j'utilise un CRC32C du fichier (XxHash32 étant privé à son package), et je passe à l'écriture du moteur.

static int hash(byte[] data, int offset, int length, int seed) {

    /** Version of a tile file {@link #readFile} returned: its CRC32C, never negative. */
    public static long version(byte[] file) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(file, 0, file.length);
        return crc.getValue();
    }

    /** Raised when the messages change shape; peers with another version do not stream (2: preview tiles). */
    public static final int VERSION = 2;