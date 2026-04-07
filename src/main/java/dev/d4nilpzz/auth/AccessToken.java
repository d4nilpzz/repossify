package dev.d4nilpzz.auth;

import java.time.Instant;
import java.util.List;

public class AccessToken {
    public int id;
    public String type;
    public final String name;
    public final String secret;
    public final String createdAt;
    public final String description;
    public final List<String> permissions; // MANAGER (m), UPLOADER (u)
    public final List<Route> routes;

    public AccessToken(int id, String type, String name, String secret, String description,
                       List<String> permissions, List<Route> routes) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.secret = secret;
        this.description = description;
        this.createdAt = Instant.now().toString();
        this.permissions = permissions;
        this.routes = routes;
    }

    public record Route(String path, String routePermission) { // READ (r), WRITE (w)
    }
}
