package dev.d4nilpzz.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;

import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;

public class ConfigController {

    private static final Path PAGE_CONFIG_PATH = Paths.get("./data/page.json");
    private static final Path REPOS_BASE_PATH = Paths.get("./data/repos");
    private static final ObjectMapper mapper = new ObjectMapper();

    public ConfigController(Javalin app) {
        app.put("/config/update", ctx -> {
            if (!Files.exists(PAGE_CONFIG_PATH)) {
                ctx.status(404).result("page.json not found");
                return;
            }

            ObjectNode oldConfig = (ObjectNode) mapper.readTree(PAGE_CONFIG_PATH.toFile());
            ObjectNode newConfig = oldConfig.deepCopy();
            JsonNode updates = ctx.bodyAsClass(JsonNode.class);

            if (!updates.isObject()) {
                ctx.status(400).result("Invalid JSON body");
                return;
            }

            updates.fields().forEachRemaining(e ->
                    newConfig.set(e.getKey(), e.getValue())
            );

            Set<String> oldRepos = extractRepoNames(oldConfig);
            Set<String> newRepos = extractRepoNames(newConfig);

            for (String repo : newRepos) {
                Path repoPath = REPOS_BASE_PATH.resolve(repo);
                if (!Files.exists(repoPath)) {
                    Files.createDirectories(repoPath);
                }
            }

            for (String repo : oldRepos) {
                if (!newRepos.contains(repo)) {
                    Path repoPath = REPOS_BASE_PATH.resolve(repo);
                    if (Files.exists(repoPath) && isEmptyDirectory(repoPath)) {
                        Files.delete(repoPath);
                    }
                }
            }

            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(PAGE_CONFIG_PATH.toFile(), newConfig);

            ctx.json(newConfig);
        });
    }

    private static Set<String> extractRepoNames(ObjectNode config) {
        Set<String> names = new HashSet<>();
        JsonNode repos = config.get("repositories");

        if (repos != null && repos.isArray()) {
            for (JsonNode repo : repos) {
                if (repo.has("name")) {
                    String name = repo.get("name").asText();
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    private static boolean isEmptyDirectory(Path dir) throws Exception {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            return !ds.iterator().hasNext();
        }
    }
}
