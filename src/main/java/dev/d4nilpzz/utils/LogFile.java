package dev.d4nilpzz.utils;

import dev.d4nilpzz.Repossify;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class LogFile {

    private static final Path LOG_DIR = Repossify.WORKING_DIR.resolve("logs");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static LocalDate currentDate;
    private static BufferedWriter writer;

    static {
        try {
            Files.createDirectories(LOG_DIR);
            rotateIfNeeded();
        } catch (IOException e) {
            throw new RuntimeException("Log init failed", e);
        }
    }

    private static synchronized void rotateIfNeeded() throws IOException {
        LocalDate now = LocalDate.now();

        if (writer == null || !now.equals(currentDate)) {
            if (writer != null) writer.close();

            currentDate = now;
            writer = Files.newBufferedWriter(
                    LOG_DIR.resolve("latest.log"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        }
    }

    private static String format(String level, Class<?> source, String msg) {
        return "[" + LocalTime.now().format(TIME) + "] "
                + "[" + level + "] "
                + "[" + source.getName() + "] "
                + msg;
    }

    private static void writeDaily(String line) throws IOException {
        Files.writeString(
                LOG_DIR.resolve(currentDate.format(DATE) + ".log"),
                line + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    public static synchronized void info(Class<?> source, String msg) {
        log("INFO", source, msg);
    }

    public static synchronized void warn(Class<?> source, String msg) {
        log("WARN", source, msg);
    }

    public static synchronized void error(Class<?> source, String msg) {
        log("ERROR", source, msg);
    }

    private static void log(String level, Class<?> source, String msg) {
        try {
            rotateIfNeeded();
            String line = format(level, source, msg);

            writer.write(line);
            writer.newLine();
            writer.flush();

            writeDaily(line);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}