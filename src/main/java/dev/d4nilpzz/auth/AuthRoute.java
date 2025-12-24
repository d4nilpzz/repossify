package dev.d4nilpzz.auth;

import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;


public class AuthRoute {
    /**
     * Obtiene el token desde header/cookie y verifica permisos de manager o write en la ruta.
     * @param ctx Context de Javalin
     * @param route Ruta que quieres proteger
     * @param tokenService TokenService para obtener el AccessToken
     * @return AccessToken válido
     */
    public static AccessToken requireManagerOrWrite(Context ctx, String route, TokenService tokenService) {
        String secret = null;

        String authHeader = ctx.header("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            secret = authHeader.substring("Bearer ".length());
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
