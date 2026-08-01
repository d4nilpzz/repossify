package dev.d4nilpzz.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.d4nilpzz.RepossifyArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and persists {@link ServerConfig}, creating the file with defaults on first run.
 */
public class ConfigService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String FILE_NAME = "configuration.json";

    private final Path path;
    private volatile ServerConfig config;

    public ConfigService(Path workingDirectory) {
        this.path = workingDirectory.resolve(FILE_NAME);
        this.config = load();
    }

    private ServerConfig load() {
        if (Files.notExists(path)) {
            ServerConfig defaults = new ServerConfig();
            try {
                save(defaults);
                LOGGER.info("Created {} with default settings", path.getFileName());
            } catch (IOException e) {
                LOGGER.warn("Cannot write {}: {}", path, e.getMessage());
            }
            return defaults;
        }

        try {
            return MAPPER.readValue(path.toFile(), ServerConfig.class);
        } catch (IOException e) {
            // Falling back to defaults keeps the server startable; refusing to boot on a
            // malformed config would strand an operator with no way to reach the dashboard.
            LOGGER.error("Cannot parse {} ({}), using defaults", path, e.getMessage());
            return new ServerConfig();
        }
    }

    public ServerConfig current() {
        return config;
    }

    public synchronized void save(ServerConfig updated) throws IOException {
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), updated);
        this.config = updated;
    }

    public synchronized ServerConfig reload() {
        this.config = load();
        return this.config;
    }

    /**
     * Applies command line overrides on top of the file. Flags win so existing invocations
     * and container entrypoints keep behaving exactly as before.
     */
    public ServerConfig withOverrides(RepossifyArgs args) {
        ServerConfig effective = config;

        if (args.port != null) {
            try {
                effective.port = Integer.parseInt(args.port.trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("Ignoring invalid --port '{}'", args.port);
            }
        }
        if (args.hostname != null && !args.hostname.isBlank()) {
            effective.hostname = args.hostname.trim();
        }
        if (args.maxRequestSize != null) {
            try {
                effective.maxRequestSize = Long.parseLong(args.maxRequestSize.trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("Ignoring invalid --max-request-size '{}'", args.maxRequestSize);
            }
        }
        return effective;
    }
}
