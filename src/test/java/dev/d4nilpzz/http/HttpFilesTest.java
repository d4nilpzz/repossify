package dev.d4nilpzz.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpFilesTest {

    @Test
    void parsesAClosedRange() {
        HttpFiles.Range range = HttpFiles.parseRange("bytes=0-99", 1000);
        assertNotNull(range);
        assertEquals(0, range.start);
        assertEquals(99, range.end);
        assertEquals(100, range.length());
    }

    @Test
    void parsesAnOpenEndedRange() {
        HttpFiles.Range range = HttpFiles.parseRange("bytes=500-", 1000);
        assertNotNull(range);
        assertEquals(500, range.start);
        assertEquals(999, range.end);
    }

    @Test
    void parsesASuffixRange() {
        HttpFiles.Range range = HttpFiles.parseRange("bytes=-100", 1000);
        assertNotNull(range);
        assertEquals(900, range.start);
        assertEquals(999, range.end);
    }

    @Test
    void clampsAnEndBeyondTheFile() {
        HttpFiles.Range range = HttpFiles.parseRange("bytes=900-5000", 1000);
        assertNotNull(range);
        assertEquals(999, range.end);
    }

    @Test
    void rejectsRangesOutsideTheFile() {
        assertSame(HttpFiles.Range.UNSATISFIABLE, HttpFiles.parseRange("bytes=2000-3000", 1000));
        assertSame(HttpFiles.Range.UNSATISFIABLE, HttpFiles.parseRange("bytes=500-100", 1000));
    }

    @Test
    void ignoresAbsentOrUnsupportedRangeHeaders() {
        assertNull(HttpFiles.parseRange(null, 1000));
        assertNull(HttpFiles.parseRange("items=0-99", 1000));
        assertNull(HttpFiles.parseRange("bytes=abc", 1000));
        // Multi-range is answered with the full entity rather than a multipart body.
        assertNull(HttpFiles.parseRange("bytes=0-49,100-149", 1000));
    }

    @Test
    void mapsMavenExtensionsToStableContentTypes() {
        assertEquals("application/java-archive", HttpFiles.contentTypeOf("demo-1.0.0.jar"));
        assertEquals("application/xml", HttpFiles.contentTypeOf("demo-1.0.0.pom"));
        assertEquals("application/xml", HttpFiles.contentTypeOf("maven-metadata.xml"));
        assertEquals("application/vnd.gradle.module+json", HttpFiles.contentTypeOf("demo-1.0.0.module"));
        assertEquals("text/plain", HttpFiles.contentTypeOf("demo-1.0.0.jar.sha1"));
        assertEquals("application/octet-stream", HttpFiles.contentTypeOf("demo-1.0.0.unknown"));
    }

    @Test
    void formatsHttpDates() {
        long epochMillis = java.time.Instant.parse("2026-08-01T00:00:00Z").toEpochMilli();
        assertEquals("Sat, 01 Aug 2026 00:00:00 GMT", HttpFiles.httpDate(epochMillis));
    }
}
