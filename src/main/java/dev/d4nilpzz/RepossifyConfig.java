package dev.d4nilpzz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

public class RepossifyConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(RepossifyConfig.class);
    private final Properties properties = new Properties();

    public RepossifyConfig(Path path) {
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            properties.load(fis);
        } catch (IOException e) {
            LOGGER.error("Error loading properties.");
            throw new RuntimeException(e);
        }
    }

    public String get(String key, String def) {
        return properties.getProperty(key, def);
    }
}
