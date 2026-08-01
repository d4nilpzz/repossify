package dev.d4nilpzz.controllers;

import dev.d4nilpzz.auth.AuthService;
import dev.d4nilpzz.utils.MetricsCompiler;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Streams host metrics to the dashboard over a websocket.
 */
public class MetricsController {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsController.class);

    private final AuthService authService;
    private final List<WsContext> clients = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "repossify-metrics");
                thread.setDaemon(true);
                return thread;
            });

    public MetricsController(AuthService authService) {
        this.authService = authService;

        scheduler.scheduleAtFixedRate(() -> {
            // Nothing is connected most of the time; skip the sampling work entirely.
            if (clients.isEmpty()) return;
            try {
                broadcast(MetricsCompiler.getMetricsJson());
            } catch (Exception e) {
                LOGGER.debug("Metrics broadcast failed: {}", e.getMessage());
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    public void registerRoutes(Javalin app) {
        app.ws("/api/metrics", ws -> {
            ws.onConnect(ctx -> {
                // Host CPU, memory and disk are operational data, so this is manager-only.
                // Authentication happens on the upgrade request; a socket that is already
                // open cannot be re-checked, which is why onClose does no verification.
                authService.requireManager(ctx.getUpgradeCtx$javalin());
                clients.add(ctx);
            });
            ws.onClose(ctx -> clients.remove(ctx));
            ws.onError(ctx -> clients.remove(ctx));
        });
    }

    private void broadcast(String message) {
        for (WsContext client : clients) {
            try {
                client.send(message);
            } catch (Exception e) {
                clients.remove(client);
            }
        }
    }

    public void stop() {
        scheduler.shutdownNow();
    }
}
