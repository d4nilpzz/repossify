package dev.d4nilpzz.http;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Guards every filesystem access that derives its target from user input.
 * <p>
 * Every controller that turns a request path, form field or query parameter into a
 * {@link Path} must go through {@link #resolve(Path, String)}; resolving by hand is how
 * {@code ../../} traversal gets in.
 */
public final class PathSafety {

    private PathSafety() {
    }

    /**
     * Resolves {@code relative} against {@code base}, guaranteeing the result stays inside base.
     *
     * @param base     directory the result must remain within
     * @param relative untrusted relative path, may use either separator and may be absolute-looking
     * @return the normalized absolute path, or {@code null} when it escapes {@code base} or is unusable
     */
    public static Path resolve(Path base, String relative) {
        if (relative == null) return null;

        String cleaned = relative.replace('\\', '/');
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);

        Path normalizedBase = base.toAbsolutePath().normalize();
        if (cleaned.isEmpty()) return normalizedBase;

        // A NUL byte or a reserved character makes the resolve throw instead of returning something odd.
        Path target;
        try {
            target = normalizedBase.resolve(cleaned).normalize();
        } catch (InvalidPathException e) {
            return null;
        }

        return target.startsWith(normalizedBase) ? target : null;
    }

    /**
     * Same as {@link #resolve(Path, String)} but rejects anything that is not a direct,
     * single-segment child of {@code base}. Used for repository names, which must never
     * be able to point at a nested or sibling directory.
     */
    public static Path resolveChild(Path base, String name) {
        if (name == null || name.isEmpty()) return null;
        if (name.contains("/") || name.contains("\\")) return null;
        if (name.equals(".") || name.equals("..")) return null;
        return resolve(base, name);
    }
}
