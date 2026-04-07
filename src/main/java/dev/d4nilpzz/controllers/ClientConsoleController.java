package dev.d4nilpzz.controllers;

import dev.d4nilpzz.auth.AuthRoute;
import dev.d4nilpzz.auth.TokenService;
import dev.d4nilpzz.console.ConsoleBridge;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ClientConsoleController {
    private final TokenService tokenService;
    private final ConsoleBridge bridge;

    private final List<WsContext> clients = new CopyOnWriteArrayList<>();

    public ClientConsoleController(TokenService tokenService, ConsoleBridge bridge) {
        this.tokenService = tokenService;
        this.bridge = bridge;
    }

    public void registerRoutes(Javalin app) {

        app.ws("/api/console/ws", ws -> {
            ws.onConnect(ctx -> {
                AuthRoute.requireManagerOrWrite(ctx.getUpgradeCtx$javalin(), "/", tokenService);
                clients.add(ctx);
            });
            ws.onClose(ctx -> {
                AuthRoute.requireManagerOrWrite(ctx.getUpgradeCtx$javalin(), "/", tokenService);
                clients.remove(ctx);
            });
        });

        app.post("/api/console/exec", ctx -> {
            AuthRoute.requireManagerOrWrite(ctx, ctx.path(), tokenService);

            String cmd = ctx.body();

            new Thread(() -> {
                String output = bridge.execute(cmd);
                broadcast(output);
            }).start();

            ctx.status(200);
        });
    }

    private void broadcast(String msg) {
        for (WsContext client : clients) {
            try {
                client.send(msg);
            } catch (Exception ignored) {
            }
        }
    }
}