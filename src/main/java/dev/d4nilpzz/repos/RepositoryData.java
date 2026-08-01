package dev.d4nilpzz.repos;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Serialized shape of {@code page.json}: the dashboard's cosmetic settings plus the
 * repository definitions.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RepositoryData {

    public String title;
    public String author;
    public String group_id;
    public String description;
    public String avatar_url;
    public String domain_url;
    public List<Link> links;
    public List<Repository> repositories;

    /* ================= PAGE ================= */

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Link {
        public String url;
        public String icon;
    }

    /* ================= REPOSITORIES ================= */

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Repository {
        public String name;
        public String path;

        /**
         * Legacy flag kept so existing {@code page.json} files keep working. When
         * {@link #visibility} is absent this is what decides access.
         */
        public Boolean isPrivate;

        /** PUBLIC, HIDDEN or PRIVATE. Takes precedence over {@link #isPrivate}. */
        public String visibility;

        /** Whether an already published release may be overwritten. Snapshots always may. */
        public boolean redeployment = false;

        /** Timestamped snapshot builds to keep per version. 0 keeps every build. */
        public int preserveSnapshots = 0;

        /** Human readable cap such as {@code "10GB"}. Null or {@code "0"} means unlimited. */
        public String storageQuota;

        /** Upstream repositories consulted when an artifact is missing locally. */
        public List<Proxy> proxied = new ArrayList<>();

        /** Populated per request by the page endpoint; never persisted to disk. */
        public List<TreeNode> tree;

        @JsonIgnore
        public Visibility resolvedVisibility() {
            Visibility fallback = Boolean.TRUE.equals(isPrivate) ? Visibility.PRIVATE : Visibility.PUBLIC;
            return Visibility.parse(visibility, fallback);
        }

        /**
         * Keeps the two visibility representations consistent so older clients reading
         * {@code isPrivate} and newer ones reading {@code visibility} agree.
         */
        public void normalize() {
            Visibility resolved = resolvedVisibility();
            this.visibility = resolved.name();
            this.isPrivate = resolved == Visibility.PRIVATE;
            if (this.path == null || this.path.isBlank()) this.path = "/" + this.name;
            if (this.proxied == null) this.proxied = new ArrayList<>();
            if (this.preserveSnapshots < 0) this.preserveSnapshots = 0;
        }
    }

    /** An upstream repository mirrored by a local one. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Proxy {
        public String url;

        /** Whether fetched artifacts are written to local storage for later requests. */
        public boolean store = true;

        /** When non-empty, only these groupId prefixes are looked up upstream. */
        public List<String> allowedGroups = new ArrayList<>();

        public int connectTimeout = 3_000;
        public int readTimeout = 15_000;
    }

    /* ================= TREE ================= */

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TreeNode {
        public String type;          // "directory" or "file"
        public String name;
        public String path;          // relative to the repositories root
        public String groupId;
        public String artifactId;
        public String version;
        public Long size;
        public List<TreeNode> children;
    }
}
