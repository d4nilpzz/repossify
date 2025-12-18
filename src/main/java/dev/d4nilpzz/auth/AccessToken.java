package dev.d4nilpzz.auth;

import java.time.Instant;
import java.util.List;

public class AccessToken {
    public Identifier identifier;
    public String name;
    public String secret;
    public String createdAt;
    public String description;
    public List<String> permissions; // MANAGER (m), UPLOADER (u)
    public List<Route> routes;

    public static class Identifier {
        public int id;
        public String type;        // PERSISTENT, TEMPORARY
    }

    public static class Route {
        public String path;
        public String routePermission; // READ (r), WRITE (w)
    }

    public AccessToken(int id, String type, String name, String secret, String description,
                       List<String> permissions, List<Route> routes) {
        this.identifier = new Identifier();
        this.identifier.id = id;
        this.identifier.type = type;
        this.name = name;
        this.secret = secret;
        this.description = description;
        this.createdAt = Instant.now().toString();
        this.permissions = permissions;
        this.routes = routes;
    }
}
