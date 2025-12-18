package dev.d4nilpzz;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

public class RepossifyConfig {
    private final Properties properties = new Properties();

    public RepossifyConfig(Path path) throws IOException {
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            properties.load(fis);
        }
    }

    public String get(String key, String def) {
        return properties.getProperty(key, def);
    }
}
