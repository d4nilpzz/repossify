package dev.d4nilpzz.repos;

/**
 * How much of a repository is exposed to callers without read permission.
 */
public enum Visibility {
    /** Anyone may browse and resolve artifacts. */
    PUBLIC,
    /** Artifacts resolve for anyone holding the exact path, but the repository is not listed. */
    HIDDEN,
    /** Every read requires a token with read access. */
    PRIVATE;

    public static Visibility parse(String raw, Visibility fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Whether an anonymous caller may resolve a known path in this repository. */
    public boolean allowsAnonymousRead() {
        return this != PRIVATE;
    }

    /** Whether the repository appears in listings for callers without read access. */
    public boolean allowsAnonymousListing() {
        return this == PUBLIC;
    }
}
