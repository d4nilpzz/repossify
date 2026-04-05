package dev.d4nilpzz.console;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public class ConsoleBridge {

    private final CommandConsole console;

    public ConsoleBridge(CommandConsole console) {
        this.console = console;
    }

    public String execute(String command) {
        PrintStream originalOut = System.out;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream tempOut = new PrintStream(output);

        System.setOut(tempOut);

        try {
            console.handleCommand(command);
        } catch (Exception ignored) {}

        System.out.flush();
        System.setOut(originalOut);

        return output.toString();
    }
}