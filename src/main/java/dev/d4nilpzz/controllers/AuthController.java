package dev.d4nilpzz.controllers;

import dev.d4nilpzz.auth.AccessToken;
import dev.d4nilpzz.auth.AuthService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import io.javalin.http.UnauthorizedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthController.class);
    private static final int SESSION_TTL_SECONDS = 60 * 60 * 8;

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public void registerRoutes(Javalin app) {
        app.post("/api/auth/signin", ctx -> {
            String secret = bearerOf(ctx);
            if (secret == null) throw new UnauthorizedResponse("Sign in requires a Bearer token");

            AccessToken token = authService.resolve(ctx).orElseThrow(() -> {
                LOGGER.warn("{} tried to sign in with invalid credentials", ctx.ip());
                return new UnauthorizedResponse("Invalid token");
            });

            ctx.cookie(sessionCookie(ctx, secret, SESSION_TTL_SECONDS));
            LOGGER.info("Session opened for '{}' from {}", token.name, ctx.ip());
            ctx.json(token);
        });

        app.post("/api/auth/signout", ctx -> {
            // Overwritten rather than removed so the attributes match the cookie that was
            // set; a browser ignores a deletion whose flags differ from the original.
            ctx.cookie(sessionCookie(ctx, "", 0));
            ctx.json(java.util.Map.of("message", "Signed out"));
        });

        app.get("/api/auth/me", ctx -> {
            AccessToken token = authService.resolve(ctx)
                    .orElseThrow(() -> new UnauthorizedResponse("Not signed in"));
            ctx.json(token);
        });
    }

    private Cookie sessionCookie(Context ctx, String value, int maxAge) {
        Cookie cookie = new Cookie(AuthService.SESSION_COOKIE, value);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setSameSite(SameSite.STRICT);
        // Only marked Secure over HTTPS: a Secure cookie is dropped outright on plain HTTP,
        // which would break every local and HTTP reverse-proxied deployment.
        cookie.setSecure(isSecure(ctx));
        return cookie;
    }

    private boolean isSecure(Context ctx) {
        if ("https".equalsIgnoreCase(ctx.scheme())) return true;
        String forwarded = ctx.header("X-Forwarded-Proto");
        return forwarded != null && forwarded.toLowerCase().startsWith("https");
    }

    private String bearerOf(Context ctx) {
        String header = ctx.header("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String secret = header.substring("Bearer ".length()).trim();
            return secret.isEmpty() ? null : secret;
        }
        return null;
    }
}
