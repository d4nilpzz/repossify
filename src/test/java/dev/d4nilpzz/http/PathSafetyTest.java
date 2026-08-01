package dev.d4nilpzz.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathSafetyTest {

    @TempDir
    Path base;

    @ParameterizedTest
    @ValueSource(strings = {
            "../outside.txt",
            "../../etc/passwd",
            "releases/../../escape.jar",
            "..\\windows\\escape.jar",
            "/../escape.jar",
            "a/b/../../../escape.jar"
    })
    void rejectsTraversal(String candidate) {
        assertNull(PathSafety.resolve(base, candidate),
                "traversal should not resolve: " + candidate);
    }

    @Test
    void resolvesPathsInsideBase() {
        Path resolved = PathSafety.resolve(base, "releases/com/example/demo/1.0.0/demo-1.0.0.jar");
        assertNotNull(resolved);
        assertTrue(resolved.startsWith(base));
        assertEquals("demo-1.0.0.jar", resolved.getFileName().toString());
    }

    @Test
    void normalizesLeadingSlashesAndSeparators() {
        Path forward = PathSafety.resolve(base, "/releases/demo.jar");
        Path backward = PathSafety.resolve(base, "\\releases\\demo.jar");
        assertEquals(forward, backward);
    }

    @Test
    void innerDotDotThatStaysInsideIsAllowed() {
        Path resolved = PathSafety.resolve(base, "releases/nested/../demo.jar");
        assertNotNull(resolved);
        assertEquals(base.resolve("releases").resolve("demo.jar"), resolved);
    }

    @Test
    void emptyPathResolvesToBase() {
        assertEquals(base.toAbsolutePath().normalize(), PathSafety.resolve(base, ""));
        assertEquals(base.toAbsolutePath().normalize(), PathSafety.resolve(base, "/"));
    }

    @Test
    void nullIsRejected() {
        assertNull(PathSafety.resolve(base, null));
    }

    @Test
    void resolveChildAcceptsOnlyDirectChildren() {
        assertNotNull(PathSafety.resolveChild(base, "releases"));
        assertNull(PathSafety.resolveChild(base, "releases/nested"));
        assertNull(PathSafety.resolveChild(base, ".."));
        assertNull(PathSafety.resolveChild(base, "."));
        assertNull(PathSafety.resolveChild(base, ""));
        assertNull(PathSafety.resolveChild(base, null));
    }
}
