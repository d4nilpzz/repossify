package dev.d4nilpzz.controllers;

import dev.d4nilpzz.auth.AuthRoute;
import dev.d4nilpzz.auth.TokenService;
import dev.d4nilpzz.utils.MetricsCompiler;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MetricsController {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final TokenService tokenService;

    private final List<WsContext> clients = new CopyOnWriteArrayList<>();


    public MetricsController(TokenService tokenService) {
        this.tokenService = tokenService;

        scheduler.scheduleAtFixedRate(() -> {
            String metricsJson = MetricsCompiler.getMetricsJson(clients);
            broadcast(metricsJson);
        }, 0, 5, TimeUnit.SECONDS);
    }

    public void registerRoutes(Javalin app) {
        app.ws("/api/metrics", ws -> {
            ws.onConnect(ctx -> {
                AuthRoute.requireManagerOrWrite(ctx.getUpgradeCtx$javalin(), "/", tokenService);
                clients.add(ctx);
            });
            ws.onClose(ctx -> {
                AuthRoute.requireManagerOrWrite(ctx.getUpgradeCtx$javalin(), "/", tokenService);
                clients.remove(ctx);
            });
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
