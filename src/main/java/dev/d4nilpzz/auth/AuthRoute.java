package dev.d4nilpzz.auth;

import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;


public class AuthRoute {
    /**
     * Retrieves the token from header/cookie and checks for manager or write permissions on the route.
     * @param ctx Javalin context
     * @param route Route you want to protect
     * @param tokenService TokenService used to obtain the AccessToken
     * @return Valid AccessToken
     */
    public static AccessToken requireManagerOrWrite(Context ctx, String route, TokenService tokenService) {
        String secret = null;

        String authHeader = ctx.header("Authorization");
        if (authHeader != null) {
            if (authHeader.startsWith("Bearer ")) {
                secret = authHeader.substring("Bearer ".length());
            } else if (authHeader.startsWith("Basic ")) {
                try {
                    String base64 = authHeader.substring("Basic ".length());
                    String decoded = new String(java.util.Base64.getDecoder().decode(base64));
                    String[] split = decoded.split(":");

                    if (split.length == 2) {
                        secret = split[1];
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }

        if (secret == null) {
            secret = ctx.cookie("repossify_session");
        }

        if (secret == null) throw new UnauthorizedResponse("Token required");

        AccessToken token;
        try {
            token = tokenService.getTokenBySecret(secret);
        } catch (Exception e) {
            throw new UnauthorizedResponse("Invalid token");
        }

        if (token == null) throw new UnauthorizedResponse("Invalid token");

        boolean isManager = token.permissions.stream()
                .anyMatch(p -> p.equalsIgnoreCase("M") || p.equalsIgnoreCase("MANAGER"));

        if (!isManager) {
            boolean hasWrite = token.routes.stream()
                    .anyMatch(r -> route.startsWith(r.path) && r.routePermission.equalsIgnoreCase("w"));

            if (!hasWrite) throw new UnauthorizedResponse("Token does not have write permission for this route");
        }

        return token;
    }

}
