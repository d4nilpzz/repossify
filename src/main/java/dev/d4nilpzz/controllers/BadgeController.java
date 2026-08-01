package dev.d4nilpzz.controllers;

import dev.d4nilpzz.repos.MetadataService;
import dev.d4nilpzz.repos.RepositoryData;
import dev.d4nilpzz.repos.RepositoryService;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Renders a shields.io-style version badge for an artifact.
 */
public class BadgeController {

    private final RepositoryService repositoryService;
    private final MetadataService metadataService;

    public BadgeController(RepositoryService repositoryService, MetadataService metadataService) {
        this.repositoryService = repositoryService;
        this.metadataService = metadataService;
    }

    public void registerRoutes(Javalin app) {
        app.get("/api/badge/latest/{repository}/{group}/{owner}/{artifact}", this::handleLatest);
    }

    private void handleLatest(Context ctx) throws IOException {
        String repositoryName = ctx.pathParam("repository");
        String group = ctx.pathParam("group");
        String owner = ctx.pathParam("owner");
        String artifactId = ctx.pathParam("artifact");

        String color = Optional.ofNullable(ctx.queryParam("color")).orElse("40c14a");
        String label = Optional.ofNullable(ctx.queryParam("label")).orElse(artifactId);
        String prefix = Optional.ofNullable(ctx.queryParam("prefix")).orElse("");
        String filter = ctx.queryParam("filter");
        String rounded = Optional.ofNullable(ctx.queryParam("r")).orElse("4");

        // Badges are embedded in public READMEs, so they only ever describe public
        // repositories; anything else would leak version numbers without authentication.
        Optional<RepositoryData.Repository> repository = repositoryService.find(repositoryName);
        boolean visible = repository
                .map(repo -> repo.resolvedVisibility().allowsAnonymousListing())
                .orElse(false);

        String version = "unknown";
        if (visible) {
            version = resolveLatest(repositoryName, group, owner, artifactId, filter).orElse("unknown");
        }

        // Badges must not be cached hard, or a README shows a stale version after a release.
        ctx.header("Cache-Control", "no-cache, max-age=60");
        ctx.contentType("image/svg+xml");
        ctx.result(renderSvg(label, prefix + version, color, rounded));
    }

    private Optional<String> resolveLatest(String repositoryName, String group, String owner,
                                           String artifactId, String filter) throws IOException {
        Optional<Path> directory =
                repositoryService.resolve(repositoryName + "/" + group + "/" + owner + "/" + artifactId);

        if (directory.isEmpty() || !Files.isDirectory(directory.get())) return Optional.empty();

        List<String> versions = metadataService.discoverVersions(directory.get(), artifactId);
        return versions.stream()
                .filter(version -> filter == null || version.equals(filter) || version.startsWith(filter + "."))
                // discoverVersions already sorts with Maven ordering, so the last entry wins;
                // the previous natural-string max reported 1.9 as newer than 1.10.
                .reduce((first, second) -> second);
    }

    private String renderSvg(String label, String version, String color, String rounded) {
        int charWidth = 8;
        int padding = 6;
        int labelWidth = label.length() * charWidth + padding * 2;
        int versionWidth = version.length() * charWidth + padding * 2;
        int totalWidth = labelWidth + versionWidth;

        String safeLabel = escape(label);
        String safeVersion = escape(version);
        String safeColor = color.replaceAll("[^0-9a-fA-F]", "");
        if (safeColor.isEmpty()) safeColor = "40c14a";

        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + totalWidth + "\" height=\"20\" role=\"img\" " +
                "aria-label=\"" + safeLabel + ": " + safeVersion + "\">" +
                "<title>" + safeLabel + ": " + safeVersion + "</title>" +
                "<linearGradient id=\"s\" x2=\"0\" y2=\"100%\">" +
                "<stop offset=\"0\" stop-color=\"#bbb\" stop-opacity=\".1\"/>" +
                "<stop offset=\"1\" stop-opacity=\".1\"/>" +
                "</linearGradient>" +
                "<clipPath id=\"r\">" +
                "<rect width=\"" + totalWidth + "\" height=\"20\" rx=\"" + escape(rounded) + "\" fill=\"#fff\"/>" +
                "</clipPath>" +
                "<g clip-path=\"url(#r)\">" +
                "<rect width=\"" + labelWidth + "\" height=\"20\" fill=\"#555\"/>" +
                "<rect x=\"" + labelWidth + "\" width=\"" + versionWidth + "\" height=\"20\" fill=\"#" + safeColor + "\"/>" +
                "<rect width=\"" + totalWidth + "\" height=\"20\" fill=\"url(#s)\"/>" +
                "</g>" +
                "<g fill=\"#fff\" font-family=\"Verdana,Geneva,DejaVu Sans,sans-serif\" font-size=\"11\">" +
                "<text x=\"" + (labelWidth / 2) + "\" y=\"14\" text-anchor=\"middle\">" + safeLabel + "</text>" +
                "<text x=\"" + (labelWidth + versionWidth / 2) + "\" y=\"14\" text-anchor=\"middle\">" + safeVersion + "</text>" +
                "</g>" +
                "</svg>";
    }

    /** Query parameters land inside the SVG, so they are escaped before being embedded. */
    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
