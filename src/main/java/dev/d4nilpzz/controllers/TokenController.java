package dev.d4nilpzz.controllers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.d4nilpzz.auth.AccessToken;
import dev.d4nilpzz.auth.AuthService;
import dev.d4nilpzz.auth.RoutePermission;
import dev.d4nilpzz.auth.TokenService;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Token management over HTTP.
 * <p>
 * Tokens could previously only be created from the interactive stdin console, which means
 * anyone running Repossify in Docker or as a service had no supported way to issue a
 * deploy token. Every endpoint here requires a manager token.
 */
public class TokenController {

    private static final Logger LOGGER = LoggerFactory.getLogger(TokenController.class);

    private final AuthService authService;
    private final TokenService tokenService;

    public TokenController(AuthService authService, TokenService tokenService) {
        this.authService = authService;
        this.tokenService = tokenService;
    }

    public void registerRoutes(Javalin app) {
        app.get("/api/tokens", this::list);
        app.post("/api/tokens", this::create);
        app.delete("/api/tokens/{name}", this::delete);
        app.put("/api/tokens/{name}", this::update);
        app.post("/api/tokens/{name}/regenerate", this::regenerate);
        app.post("/api/tokens/{name}/routes", this::addRoute);
        app.delete("/api/tokens/{name}/routes", this::removeRoute);
    }

    private void list(Context ctx) throws Exception {
        authService.requireManager(ctx);
        ctx.json(tokenService.listTokens());
    }

    private void create(Context ctx) throws Exception {
        authService.requireManager(ctx);
        TokenRequest request = ctx.bodyAsClass(TokenRequest.class);

        if (request.name == null || request.name.isBlank()) {
            throw new BadRequestResponse("Token name is required");
        }

        // Generated here rather than inside the service so the plaintext can be returned;
        // it is the only moment it is ever available.
        String secret = (request.secret == null || request.secret.isBlank())
                ? TokenService.generateSecret()
                : request.secret;

        try {
            tokenService.createToken(request.name, normalize(request.permissions), secret,
                    request.description == null ? "Created via API" : request.description);
        } catch (IllegalArgumentException e) {
            throw new ConflictResponse(e.getMessage());
        }

        if (request.routes != null) {
            for (RouteRequest route : request.routes) {
                applyRoute(request.name, route);
            }
        }

        LOGGER.info("Token '{}' created by {}", request.name, ctx.ip());
        // Re-read so the response carries the routes that were just applied.
        ctx.status(201).json(Map.of(
                "token", tokenService.getTokenByName(request.name),
                "secret", secret,
                "warning", "This secret is shown once and cannot be recovered."
        ));
    }

    private void update(Context ctx) throws Exception {
        authService.requireManager(ctx);
        String name = ctx.pathParam("name");
        requireExisting(name);

        TokenRequest request = ctx.bodyAsClass(TokenRequest.class);

        if (request.permissions != null) {
            tokenService.updateTokenPermissions(name, normalize(request.permissions));
        }
        if (request.name != null && !request.name.isBlank() && !request.name.equals(name)) {
            try {
                tokenService.renameToken(name, request.name);
                name = request.name;
            } catch (IllegalArgumentException e) {
                throw new ConflictResponse(e.getMessage());
            }
        }

        LOGGER.info("Token '{}' updated by {}", name, ctx.ip());
        ctx.json(tokenService.getTokenByName(name));
    }

    private void delete(Context ctx) throws Exception {
        authService.requireManager(ctx);
        String name = ctx.pathParam("name");
        requireExisting(name);

        // Removing the last manager would lock everyone out of the dashboard and this API.
        if (isLastManager(name)) {
            throw new ConflictResponse("Refusing to delete the only manager token");
        }

        tokenService.deleteTokenByName(name);
        LOGGER.info("Token '{}' deleted by {}", name, ctx.ip());
        ctx.status(204);
    }

    private void regenerate(Context ctx) throws Exception {
        authService.requireManager(ctx);
        String name = ctx.pathParam("name");
        requireExisting(name);

        String secret = tokenService.regenerateTokenSecret(name);
        LOGGER.info("Token '{}' secret regenerated by {}", name, ctx.ip());
        ctx.json(Map.of(
                "name", name,
                "secret", secret,
                "warning", "This secret is shown once and cannot be recovered."
        ));
    }

    private void addRoute(Context ctx) throws Exception {
        authService.requireManager(ctx);
        String name = ctx.pathParam("name");
        requireExisting(name);

        applyRoute(name, ctx.bodyAsClass(RouteRequest.class));
        ctx.json(tokenService.getTokenByName(name));
    }

    private void removeRoute(Context ctx) throws Exception {
        authService.requireManager(ctx);
        String name = ctx.pathParam("name");
        requireExisting(name);

        String path = ctx.queryParam("path");
        if (path == null || path.isBlank()) throw new BadRequestResponse("Query parameter 'path' is required");

        tokenService.removeRouteFromToken(name, path);
        ctx.json(tokenService.getTokenByName(name));
    }

    private void applyRoute(String tokenName, RouteRequest route) throws Exception {
        if (route == null || route.path == null || route.path.isBlank()) {
            throw new BadRequestResponse("Route path is required");
        }
        if (RoutePermission.parse(route.permission) == null) {
            throw new BadRequestResponse("Route permission must be 'r' or 'w'");
        }
        tokenService.addRouteToToken(tokenName, route.path, route.permission);
    }

    private void requireExisting(String name) throws Exception {
        if (tokenService.getTokenByName(name) == null) {
            throw new NotFoundResponse("Token '" + name + "' does not exist");
        }
    }

    private boolean isLastManager(String name) throws Exception {
        List<AccessToken> managers = tokenService.listTokens().stream()
                .filter(token -> token.isManager)
                .toList();
        return managers.size() == 1 && managers.getFirst().name.equals(name);
    }

    private static List<String> normalize(List<String> permissions) {
        List<String> result = new ArrayList<>();
        if (permissions == null) return result;
        for (String permission : permissions) {
            if (permission == null || permission.isBlank()) continue;
            result.add(permission.trim().toUpperCase());
        }
        return result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenRequest {
        public String name;
        public String secret;
        public String description;
        public List<String> permissions;
        public List<RouteRequest> routes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RouteRequest {
        public String path;
        public String permission;
    }
}
