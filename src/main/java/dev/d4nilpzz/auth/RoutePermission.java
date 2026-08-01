package dev.d4nilpzz.auth;

/**
 * Permission a token holds over a route prefix.
 * <p>
 * {@link #WRITE} implies {@link #READ}: a token allowed to deploy to a path can always
 * resolve from it, which is what every CI setup expects.
 */
public enum RoutePermission {
    READ("r"),
    WRITE("w");

    private final String shortcut;

    RoutePermission(String shortcut) {
        this.shortcut = shortcut;
    }

    public static RoutePermission parse(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase();
        return switch (value) {
            case "r", "read" -> READ;
            case "w", "write" -> WRITE;
            default -> null;
        };
    }

    public String shortcut() {
        return shortcut;
    }

    /** {@code WRITE.covers(READ)} is true; {@code READ.covers(WRITE)} is not. */
    public boolean covers(RoutePermission required) {
        return this == WRITE || this == required;
    }
}
