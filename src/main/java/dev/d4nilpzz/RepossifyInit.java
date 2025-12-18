package dev.d4nilpzz;

import java.io.*;
import java.nio.file.*;

/**
 * RepossifyInit initializes directories and copies template files
 * with a visible spinner for at least 2 seconds per file.
 */
public class RepossifyInit {

    private static final String BASE_DIR = "./";
    private static final String[] DIRS = {"data/repos"};
    private static final String[][] FILES_TO_COPY = {
            {"template/repossify.properties", "repossify.properties"},
            {"template/data/page.json", "data/page.json"},
            {"template/data/repossify.db", "data/repossify.db"}
    };
    private static final char[] SPINNER = {'|', '/', '-', '\\'};

    public static void init() {
        try {
            for (String dir : DIRS) {
                Path path = Paths.get(BASE_DIR, dir);
                if (!Files.exists(path)) {
                    Files.createDirectories(path);
                    System.out.println("Directory created: " + path);
                }
            }

            ClassLoader cl = RepossifyInit.class.getClassLoader();

            for (String[] filePair : FILES_TO_COPY) {
                String resourcePath = filePair[0];
                String targetPath = filePair[1];

                InputStream is = cl.getResourceAsStream(resourcePath);
                if (is == null) {
                    System.err.println("Resource not found: " + resourcePath);
                    continue;
                }

                Path target = Paths.get(BASE_DIR, targetPath);

                long startTime = System.currentTimeMillis();
                int spinnerIndex = 0;

                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);

                while (System.currentTimeMillis() - startTime < 500) {
                    System.out.print("\rCopying " + targetPath + " " + SPINNER[spinnerIndex % SPINNER.length]);
                    spinnerIndex++;
                    Thread.sleep(100);
                }

                System.out.print("\rFile copied: " + targetPath + " ✅\n");
            }

            System.out.println("Repossify initialized successfully.");

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        init();
    }
}
