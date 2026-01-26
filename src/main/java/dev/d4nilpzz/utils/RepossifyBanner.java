package dev.d4nilpzz.utils;

public class RepossifyBanner {

    public static void print(String version, String[] authors) {
        String authorsStr = String.join(", ", authors);
        System.out.println("""
                  \s
                  _____                          _  __              Repossify %version% \s
                 |  __ \\                        (_)/ _|             Build by %authors% \s
                 | |__) |___ _ __   ___  ___ ___ _| |_ _   _\s
                 |  _  // _ \\ '_ \\ / _ \\/ __/ __| |  _| | | |
                 | | \\ \\  __/ |_) | (_) \\__ \\__ \\ | | | |_| |
                 |_|  \\_\\___| .__/ \\___/|___/___/_|_|  \\__, |
                            | |                         __/ |
                            |_|                        |___/\s
                """
                .replace("%authors%", authorsStr)
                .replace("%version%", version)
        );
    }
}
