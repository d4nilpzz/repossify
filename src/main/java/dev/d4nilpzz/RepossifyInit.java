package dev.d4nilpzz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class RepossifyInit {
    private static final Logger LOGGER = LoggerFactory.getLogger(RepossifyInit.class);

    private static final String[] DIRS = {
            "logs",
            "repos",
            "plugins",
            "repos/private",
            "repos/releases"
    };

    private static final String[][] FILES_TO_COPY = {
            {"template/data/page.json", "page.json"},
            {"template/data/repossify.db", "repossify.db"}
    };

    private static final char[] SPINNER = {'|', '/', '-', '\\'};
    private static final long MIN_SPINNER_TIME = 100;

    public static void init(Path workingDir) {
        try {
            for (String dir : DIRS) {
                Path path = workingDir.resolve(dir);
                if (Files.notExists(path)) {
                    Files.createDirectories(path);
                    LOGGER.info("Directory created: {}", path);
                }
            }

            ClassLoader cl = RepossifyInit.class.getClassLoader();

            for (String[] filePair : FILES_TO_COPY) {
                String resourcePath = filePair[0];
                String targetPath = filePair[1];

                Path target = workingDir.resolve(targetPath);
                Files.createDirectories(target.getParent());

                long start = System.currentTimeMillis();
                int i = 0;

                try (InputStream is = cl.getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        LOGGER.error("Resource not found: {}", resourcePath);
                        continue;
                    }

                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                }

                while (System.currentTimeMillis() - start < MIN_SPINNER_TIME) {
                    System.out.print("\rCopying " + targetPath + " " + SPINNER[i++ % SPINNER.length]);
                    Thread.sleep(10);
                }

                System.out.print("\r");
                LOGGER.info("File copied: {}", targetPath);
            }

            LOGGER.info("Repossify initialized successfully.");

        } catch (Exception e) {
            LOGGER.error("Error initializing Repossify", e);
        }
    }
}
