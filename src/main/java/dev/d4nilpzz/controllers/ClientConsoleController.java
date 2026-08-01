package dev.d4nilpzz.controllers;

import dev.d4nilpzz.auth.AccessToken;
import dev.d4nilpzz.auth.AuthService;
import dev.d4nilpzz.console.ConsoleBridge;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Exposes the administrative console over HTTP for the dashboard's terminal tab.
 * <p>
 * The console can create and delete tokens, so it is manager-only. It previously accepted
 * any token holding a write route that happened to prefix {@code /api/console/exec}, which
 * turned a deploy token into a way to mint a manager token.
 */
public class ClientConsoleController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientConsoleController.class);

    private final AuthService authService;
    private final ConsoleBridge bridge;
    private final List<WsContext> clients = new CopyOnWriteArrayList<>();

    public ClientConsoleController(AuthService authService, ConsoleBridge bridge) {
        this.authService = authService;
        this.bridge = bridge;
    }

    public void registerRoutes(Javalin app) {
        app.ws("/api/console/ws", ws -> {
            ws.onConnect(ctx -> {
                authService.requireManager(ctx.getUpgradeCtx$javalin());
                clients.add(ctx);
            });
            ws.onClose(ctx -> clients.remove(ctx));
            ws.onError(ctx -> clients.remove(ctx));
        });

        app.post("/api/console/exec", this::execute);
    }

    private void execute(Context ctx) {
        AccessToken token = authService.requireManager(ctx);

        String command = ctx.body().trim();
        if (command.isEmpty()) {
            ctx.status(400);
            return;
        }

        LOGGER.info("Console command '{}' issued by '{}' from {}", command, token.name, ctx.ip());

        // Run inline rather than on a detached thread: the console mutates shared state, and
        // the previous fire-and-forget thread reported success before the command had run.
        String output = bridge.execute(command);
        broadcast(output);
        ctx.contentType("text/plain").result(output);
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
}
