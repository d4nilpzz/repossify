package dev.d4nilpzz.controllers;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

public class BadgeController {

    private static final Path BASE_PATH = Paths.get("./data/repos");

    public BadgeController(Javalin app) {
        app.get("/api/badge/latest/{type}/{channel}/{owner}/{repo}", this::handleLatest);
    }

    private void handleLatest(Context ctx) {
        String type    = ctx.pathParam("type");
        String channel = ctx.pathParam("channel");
        String owner   = ctx.pathParam("owner");
        String repo    = ctx.pathParam("repo");

        String color  = ctx.queryParam("color");
        String label  = ctx.queryParam("label");
        String prefix = ctx.queryParam("prefix");
        String filter = ctx.queryParam("filter");
        String rounded = ctx.queryParam("r");

        if (color == null)  color = "40c14a";
        if (label == null)  label = repo;
        if (prefix == null) prefix = "";
        if (rounded == null) rounded = "4";

        Path versionsDir = BASE_PATH
                .resolve(type)
                .resolve(channel)
                .resolve(owner)
                .resolve(repo);

        if (!Files.isDirectory(versionsDir)) {
            ctx.contentType("image/svg+xml");
            String svg = svgBuilder(label,"unknown", color, rounded);
            ctx.result(svg);
            return;
        }

        try {
            String latestVersion = Files.list(versionsDir)
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(v -> filter == null || v.startsWith(filter + ".") || v.equals(filter))
                    .max(Comparator.naturalOrder())
                    .orElse("unknown");

            String svg = svgBuilder(label, prefix + latestVersion, color, rounded);

            ctx.contentType("image/svg+xml");
            ctx.result(svg);

        } catch (IOException e) {
            ctx.status(500).result("Internal error");
        }
    }

    private String svgBuilder(String label, String version, String color, String rounded) {
        int charWidth = 8;
        int padding = 6;
        int labelWidth = label.length() * charWidth + padding * 2;
        int versionWidth = version.length() * charWidth + padding * 2;
        int totalWidth = labelWidth + versionWidth;

        rounded = rounded == null ? "4" : rounded;

        String svg =
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + totalWidth + "\" height=\"20\" role=\"img\" aria-label=\"" + label + ": " + version + "\">" +
                        "<title>" + label + ": " + version + "</title>" +
                        "<linearGradient id=\"s\" x2=\"0\" y2=\"100%\">" +
                        "<stop offset=\"0\" stop-color=\"#bbb\" stop-opacity=\".1\"/>" +
                        "<stop offset=\"1\" stop-opacity=\".1\"/>" +
                        "</linearGradient>" +
                        "<clipPath id=\"r\">" +
                        "<rect width=\"" + totalWidth + "\" height=\"20\" rx=\"" + rounded + "\" fill=\"#fff\"/>" +
                        "</clipPath>" +
                        "<g clip-path=\"url(#r)\">" +
                        "<rect width=\"" + labelWidth + "\" height=\"20\" fill=\"#555\"/>" +
                        "<rect x=\"" + labelWidth + "\" width=\"" + versionWidth + "\" height=\"20\" fill=\"#" + color + "\"/>" +
                        "<rect width=\"" + totalWidth + "\" height=\"20\" fill=\"url(#s)\"/>" +
                        "</g>" +
                        "<g fill=\"#fff\" font-family=\"Verdana,Geneva,DejaVu Sans,sans-serif\" font-size=\"11\">" +
                        "<text x=\"" + (labelWidth / 2) + "\" y=\"14\" text-anchor=\"middle\">" + label + "</text>" +
                        "<text x=\"" + (labelWidth + versionWidth / 2) + "\" y=\"14\" text-anchor=\"middle\">" + version + "</text>" +
                        "</g>" +
                        "</svg>";

        return svg;
    }
}
