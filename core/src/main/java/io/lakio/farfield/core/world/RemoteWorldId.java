package io.lakio.farfield.core.world;

import java.util.Locale;

/**
 * Identity of a world on a remote server without Farfield, whose LODs the
 * client builds itself (ADR-022, client mode): the server address as typed,
 * normalized, plus the hashed seed the server sends at login and respawn.
 * The seed hash tells apart the worlds behind one address (proxies). The
 * dimension is a folder below this identity.
 */
public final class RemoteWorldId {

    public static final int DEFAULT_PORT = 25565;

    private RemoteWorldId() {
    }

    /**
     * Normalizes an address for a folder name: lower case, default port
     * made explicit, characters other than letters, digits, {@code .} and
     * {@code -} replaced by {@code _} (the port separator becomes {@code _}).
     */
    public static String addressFolder(String address) {
        String a = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty()) {
            return "unknown";
        }
        boolean ipv6 = a.startsWith("[");
        int colon = ipv6 ? a.indexOf("]:") + 1 : a.lastIndexOf(':');
        if (colon <= 0 || (!ipv6 && a.indexOf(':') != colon)) {
            a = a + ":" + DEFAULT_PORT;
        }
        StringBuilder sb = new StringBuilder(a.length());
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' ? c : '_');
        }
        return sb.toString();
    }

    /** The seed hash as a folder name: 16 hex digits. */
    public static String seedFolder(long hashedSeed) {
        return String.format(Locale.ROOT, "%016x", hashedSeed);
    }

    /** A dimension key ({@code namespace:path}) as a folder name. */
    public static String dimensionFolder(String dimensionKey) {
        return dimensionKey.toLowerCase(Locale.ROOT).replace(':', '_').replace('/', '_');
    }
}
