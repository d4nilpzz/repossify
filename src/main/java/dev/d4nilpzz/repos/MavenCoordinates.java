package dev.d4nilpzz.repos;

import java.nio.file.Path;

/**
 * Maven coordinates recovered from a repository-relative path such as
 * {@code releases/com/example/demo/1.0.0/demo-1.0.0.jar}.
 */
public record MavenCoordinates(String repository, String groupId, String artifactId, String version) {

    public boolean isSnapshot() {
        return version != null && version.endsWith("-SNAPSHOT");
    }

    /** Version with the {@code -SNAPSHOT} suffix removed, used to match timestamped builds. */
    public String baseVersion() {
        if (version == null) return null;
        return isSnapshot() ? version.substring(0, version.length() - "-SNAPSHOT".length()) : version;
    }

    /**
     * Parses the coordinates of a deployed <em>file</em>, whose path is
     * {@code <repo>/<group as directories>/<artifactId>/<version>/<filename>}.
     *
     * @return the coordinates, or null when the path is too shallow to carry them
     */
    public static MavenCoordinates ofFile(Path repositoriesRoot, Path file) {
        Path relative = repositoriesRoot.relativize(file);
        int count = relative.getNameCount();

        // repo + at least one group segment + artifactId + version + filename
        if (count < 5) return null;

        String repository = relative.getName(0).toString();
        String version = relative.getName(count - 2).toString();
        String artifactId = relative.getName(count - 3).toString();
        String groupId = relative.subpath(1, count - 3).toString().replace('\\', '.').replace('/', '.');

        return new MavenCoordinates(repository, groupId, artifactId, version);
    }

    /**
     * Parses the coordinates of an artifact <em>directory</em>, whose path is
     * {@code <repo>/<group as directories>/<artifactId>}.
     */
    public static MavenCoordinates ofArtifactDirectory(Path repositoriesRoot, Path directory) {
        Path relative = repositoriesRoot.relativize(directory);
        int count = relative.getNameCount();

        if (count < 3) return null;

        String repository = relative.getName(0).toString();
        String artifactId = relative.getName(count - 1).toString();
        String groupId = relative.subpath(1, count - 1).toString().replace('\\', '.').replace('/', '.');

        return new MavenCoordinates(repository, groupId, artifactId, null);
    }
}
