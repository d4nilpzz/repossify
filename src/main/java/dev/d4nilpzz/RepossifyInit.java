package dev.d4nilpzz;

import dev.d4nilpzz.config.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class RepossifyInit {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepossifyInit.class);

    private static final String[] DIRS = {
            "content",
            "logs",
            "repositories",
            "plugins",
            "repositories/private",
            "repositories/releases",
            "repositories/snapshots"
    };

    private static final String[][] FILES_TO_COPY = {
            {"template/data/page.json", "page.json"},
            {"template/data/repossify.db", "repossify.db"}
    };

    public static void init(Path workingDir) {
        try {
            for (String dir : DIRS) {
                Path path = workingDir.resolve(dir);
                if (Files.notExists(path)) {
                    Files.createDirectories(path);
                    LOGGER.info("Directory created: {}", path);
                }
            }

            ClassLoader classLoader = RepossifyInit.class.getClassLoader();

            for (String[] filePair : FILES_TO_COPY) {
                String resourcePath = filePair[0];
                Path target = workingDir.resolve(filePair[1]);

                // Never clobber existing data: init also runs when only one file is missing,
                // and overwriting page.json would discard the operator's repository setup.
                if (Files.exists(target)) {
                    LOGGER.info("Keeping existing {}", filePair[1]);
                    continue;
                }

                Files.createDirectories(target.getParent());
                try (InputStream stream = classLoader.getResourceAsStream(resourcePath)) {
                    if (stream == null) {
                        LOGGER.error("Resource not found: {}", resourcePath);
                        continue;
                    }
                    Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
                }
                LOGGER.info("File created: {}", filePair[1]);
            }

            // Writes configuration.json with defaults when it does not exist yet.
            new ConfigService(workingDir);

            LOGGER.info("Repossify initialized successfully.");
        } catch (Exception e) {
            LOGGER.error("Error initializing Repossify", e);
        }
    }
}
