package dev.d4nilpzz.http;

import io.javalin.http.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Serves repository files the way a Maven client expects.
 * <p>
 * The previous implementation streamed the file with only a content type, so every build
 * re-downloaded every dependency: without {@code ETag} / {@code Last-Modified} a client has
 * nothing to revalidate against, and without {@code Accept-Ranges} a large jar cannot be
 * resumed.
 */
public final class HttpFiles {

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'").withZone(ZoneOffset.UTC);

    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Content types that must never be gzipped.
     * <p>
     * Jars and zips are already compressed, so re-compressing them only burns CPU. More
     * importantly, compression rewrites the body after {@code Content-Length} has been set,
     * and a response whose declared length does not match its body makes Maven fail with
     * "Premature end of Content-Length delimited message body".
     */
    public static final List<String> INCOMPRESSIBLE_TYPES = List.of(
            "application/java-archive",
            "application/zip",
            "application/gzip",
            "application/x-tar",
            "application/octet-stream"
    );

    private HttpFiles() {
    }

    /** Whether a response of this content type may be compressed on the way out. */
    public static boolean isCompressible(String contentType) {
        if (contentType == null) return false;
        return INCOMPRESSIBLE_TYPES.stream().noneMatch(contentType::startsWith);
    }

    /**
     * Content type by extension. {@link Files#probeContentType} is backed by the Windows
     * registry on this platform and returns inconsistent results for {@code .jar} and
     * {@code .pom}, so the mapping is explicit.
     */
    public static String contentTypeOf(String filename) {
        String name = filename.toLowerCase();
        if (name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear")) {
            return "application/java-archive";
        }
        if (name.endsWith(".pom") || name.endsWith(".xml")) return "application/xml";
        if (name.endsWith(".module")) return "application/vnd.gradle.module+json";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".zip")) return "application/zip";
        if (name.endsWith(".tar")) return "application/x-tar";
        if (name.endsWith(".gz")) return "application/gzip";
        if (name.endsWith(".md5") || name.endsWith(".sha1")
                || name.endsWith(".sha256") || name.endsWith(".sha512")
                || name.endsWith(".asc") || name.endsWith(".txt")) {
            return "text/plain";
        }
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    public static String etagOf(Path file) throws IOException {
        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();
        return "\"" + Long.toHexString(size) + "-" + Long.toHexString(modified) + "\"";
    }

    public static String httpDate(long epochMillis) {
        return HTTP_DATE.format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * Writes validator and caching headers. Released artifacts are immutable once published,
     * so they get a long-lived cache entry; snapshots and metadata must be revalidated.
     */
    public static void writeValidators(Context ctx, Path file) throws IOException {
        long modified = Files.getLastModifiedTime(file).toMillis();
        ctx.header("ETag", etagOf(file));
        ctx.header("Last-Modified", httpDate(modified));
        ctx.header("Accept-Ranges", "bytes");

        String path = file.toString().replace('\\', '/');
        boolean mutable = path.contains("-SNAPSHOT")
                || file.getFileName().toString().startsWith("maven-metadata");
        ctx.header("Cache-Control", mutable ? "no-cache, must-revalidate" : "public, max-age=604800, immutable");
    }

    /**
     * Answers a conditional request. Returns true when the response is complete (304) and
     * the caller must not write a body.
     */
    public static boolean handleConditional(Context ctx, Path file) throws IOException {
        String etag = etagOf(file);
        String ifNoneMatch = ctx.header("If-None-Match");

        if (ifNoneMatch != null) {
            for (String candidate : ifNoneMatch.split(",")) {
                String trimmed = candidate.trim();
                if (trimmed.startsWith("W/")) trimmed = trimmed.substring(2);
                if (trimmed.equals("*") || trimmed.equals(etag)) {
                    ctx.status(304);
                    return true;
                }
            }
            // A present but non-matching If-None-Match wins over If-Modified-Since.
            return false;
        }

        String ifModifiedSince = ctx.header("If-Modified-Since");
        if (ifModifiedSince != null) {
            try {
                long since = ZonedDateTime.parse(ifModifiedSince, HTTP_DATE).toInstant().toEpochMilli();
                // HTTP dates have second precision; compare at that granularity.
                if (Files.getLastModifiedTime(file).toMillis() / 1000 <= since / 1000) {
                    ctx.status(304);
                    return true;
                }
            } catch (DateTimeParseException ignored) {
                // Malformed header, fall through and send the body.
            }
        }
        return false;
    }

    /**
     * Streams {@code file}, honouring a single-range {@code Range} header.
     * Multi-range requests fall back to the full body, which is a valid response.
     */
    public static void serve(Context ctx, Path file, boolean includeBody) throws IOException {
        long size = Files.size(file);
        String contentType = contentTypeOf(file.getFileName().toString());
        ctx.contentType(contentType);
        writeValidators(ctx, file);

        Range range = parseRange(ctx.header("Range"), size);

        if (range == Range.UNSATISFIABLE) {
            ctx.status(416);
            ctx.header("Content-Range", "bytes */" + size);
            return;
        }

        // Declaring a length that a later compression pass would invalidate breaks the
        // client, so it is only sent when the body is guaranteed to travel verbatim. A HEAD
        // has no body to compress, so it can always carry the real size.
        boolean canDeclareLength = !includeBody || !isCompressible(contentType);

        if (range == null) {
            if (canDeclareLength) ctx.header("Content-Length", String.valueOf(size));
            ctx.status(200);
            if (includeBody) writeSlice(ctx, file, 0, size);
            return;
        }

        ctx.status(206);
        ctx.header("Content-Range", "bytes " + range.start + "-" + range.end + "/" + size);
        if (canDeclareLength) ctx.header("Content-Length", String.valueOf(range.length()));
        if (includeBody) writeSlice(ctx, file, range.start, range.length());
    }

    private static void writeSlice(Context ctx, Path file, long offset, long length) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            if (offset > 0) in.skipNBytes(offset);

            OutputStream out = ctx.outputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = length;

            while (remaining > 0) {
                int wanted = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, wanted);
                if (read < 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
            out.flush();
        }
    }

    /**
     * Parses a {@code Range} header.
     *
     * @return null when no usable range was requested, {@link Range#UNSATISFIABLE} when the
     * range falls outside the file, otherwise the resolved byte range
     */
    static Range parseRange(String header, long size) {
        if (header == null || !header.startsWith("bytes=")) return null;

        String spec = header.substring("bytes=".length()).trim();
        if (spec.contains(",")) return null; // multi-range: serve the whole entity instead
        int dash = spec.indexOf('-');
        if (dash < 0) return null;

        String rawStart = spec.substring(0, dash).trim();
        String rawEnd = spec.substring(dash + 1).trim();

        try {
            long start;
            long end;
            if (rawStart.isEmpty()) {
                // Suffix form "-500": the last 500 bytes.
                if (rawEnd.isEmpty()) return null;
                long suffix = Long.parseLong(rawEnd);
                if (suffix <= 0) return Range.UNSATISFIABLE;
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(rawStart);
                end = rawEnd.isEmpty() ? size - 1 : Long.parseLong(rawEnd);
            }

            if (start < 0 || start >= size || end < start) return Range.UNSATISFIABLE;
            return new Range(start, Math.min(end, size - 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static final class Range {
        static final Range UNSATISFIABLE = new Range(-1, -1);

        final long start;
        final long end;

        Range(long start, long end) {
            this.start = start;
            this.end = end;
        }

        long length() {
            return end - start + 1;
        }
    }
}
