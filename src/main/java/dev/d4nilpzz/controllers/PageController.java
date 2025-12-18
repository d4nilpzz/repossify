package dev.d4nilpzz.controllers;

import dev.d4nilpzz.repos.RepositoryData;
import io.javalin.Javalin;
import io.javalin.http.ContentType;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class PageController {

    public PageController(Javalin app) {

        app.get("/", ctx -> {
            try (InputStream is = getClass().getResourceAsStream("/static/index.html")) {
                ctx.contentType("text/html");
                assert is != null;
                ctx.result(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
        });

        app.get("/api/page", ctx -> {
            try {
                RepositoryData data = RepositoryData.loadPageConfig();
                data.repositories = RepositoryData.loadRepositories();
                ctx.contentType(ContentType.APPLICATION_JSON);
                ctx.json(data);
            } catch (Exception e) {
                e.printStackTrace();
                ctx.status(500).result("{\"error\":\"Cannot load page or repos\"}");
            }
        });
    }
}
