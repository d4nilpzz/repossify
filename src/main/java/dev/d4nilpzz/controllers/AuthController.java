package dev.d4nilpzz.controllers;

import dev.d4nilpzz.auth.AccessToken;
import dev.d4nilpzz.auth.TokenService;
import io.javalin.Javalin;
import io.javalin.http.Cookie;
import io.javalin.http.UnauthorizedResponse;

public class AuthController {
    private static final String SESSION_COOKIE = "repossify_session";
    private static final int SESSION_TTL_SECONDS = 60 * 30;

    private final TokenService tokenService;

    public AuthController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    public void registerRoutes(Javalin app) {
        app.before(ctx -> {
            String secret = null;

            String authHeader = ctx.header("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                secret = authHeader.substring("Bearer ".length());
            }

            if (secret == null) {
                secret = ctx.cookie(SESSION_COOKIE);
            }

            if (secret == null) return;

            AccessToken token = tokenService.getTokenBySecret(secret);
            if (token != null) {
                ctx.attribute("token", token);
            }
        });

        app.post("/api/auth/signin", ctx -> {
            String authHeader = ctx.header("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new UnauthorizedResponse("Missing token");
            }

            String secret = authHeader.substring("Bearer ".length());
            AccessToken token = tokenService.getTokenBySecret(secret);
            if (token == null) {
                throw new UnauthorizedResponse("Invalid token");
            }

            Cookie cookie = new Cookie(SESSION_COOKIE, secret);
            cookie.setHttpOnly(true);
            cookie.setPath("/");
            cookie.setMaxAge(SESSION_TTL_SECONDS);

            ctx.cookie(cookie);
            ctx.json(token);
        });

        app.post("/api/auth/signout", ctx -> {
            AccessToken token = ctx.attribute("token");
            if (token == null) {
                throw new UnauthorizedResponse("No active session");
            }

            ctx.removeCookie(SESSION_COOKIE);
            ctx.json("Signed out");
        });

        app.get("/api/auth/me", ctx -> {
            AccessToken token = ctx.attribute("token");
            if (token == null) {
                throw new UnauthorizedResponse("Not signed in");
            }

            ctx.json(token);
        });
    }
}
