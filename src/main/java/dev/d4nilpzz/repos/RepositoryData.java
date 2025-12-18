package dev.d4nilpzz.repos;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class RepositoryData {

    private static final ObjectMapper mapper = new ObjectMapper();
    public String title;
    public String author;
    public String group_id;
    public String description;
    public String avatar_url;
    public List<Link> links;
    public List<Repository> repositories;

    public static RepositoryData loadPageConfig() throws IOException {
        return mapper.readValue(new File("./data/page.json"), RepositoryData.class);
    }

    private static List<TreeNode> loadRepoTree(Path rootPath, Path currentPath, String basePath) throws IOException {
        List<TreeNode> nodes = new ArrayList<>();
        if (!Files.exists(currentPath) || !Files.isDirectory(currentPath)) return nodes;

        Files.list(currentPath)
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(path -> {
                    TreeNode node = new TreeNode();
                    node.name = path.getFileName().toString();
                    node.path = basePath + "/" + node.name;

                    if (Files.isDirectory(path)) {
                        node.type = "directory";
                        try {
                            node.children = loadRepoTree(rootPath, path, node.path);
                        } catch (IOException e) {
                            node.children = new ArrayList<>();
                        }
                        node.size = null;
                        node.version = null;
                        node.artifactId = null;
                        node.groupId = null;
                    } else {
                        node.type = "file";
                        node.size = path.toFile().length();
                        node.children = null;

                        // only .jar/.pom files get version
                        if (node.name.endsWith(".jar") || node.name.endsWith(".pom")) {
                            Path versionDir = path.getParent();
                            node.version = versionDir.getFileName().toString();
                        }
                    }
                    nodes.add(node);
                });

        return nodes;
    }

    public static List<Repository> loadRepositories() throws IOException {
        List<Repository> repos = new ArrayList<>();
        File reposDir = new File("./data/repos");

        if (!reposDir.exists() || !reposDir.isDirectory()) {
            return repos;
        }

        for (File repoDir : reposDir.listFiles(File::isDirectory)) {
            Repository repo = new Repository();
            repo.name = repoDir.getName();
            repo.path = "/" + repoDir.getName();

            Path releasesRoot = repoDir.toPath();
            repo.tree = loadRepoTree(repoDir.toPath(), repoDir.toPath(), "/" + repoDir.getName());

            repos.add(repo);
        }

        return repos;
    }

    /* ================= PAGE ================= */

    public static class Link {
        public String url;
        public String icon;
    }

    /* ================= TREE ================= */

    public static class Repository {
        public String name;
        public String path;
        public List<TreeNode> tree;
    }


    /* ================= REPOSITORIES ================= */

    public static class TreeNode {
        public String type;          // "directory" or "file"
        public String name;
        public String path;          // relative from releases/
        public String groupId;
        public String artifactId;
        public String version;
        public Long size;
        public List<TreeNode> children;
    }
}
