package dev.d4nilpzz;

import dev.d4nilpzz.params.Param;

public class RepossifyArgs {

    @Param(names = {"--help", "-?"}, description = "Show the available options")
    public boolean help;

    @Param(names = {"--version"}, description = "Print the version of Repossify")
    public boolean version;

    /**
     * Documented in the README since 1.0.0 but never actually declared, so the flag was
     * silently ignored and the server started instead of initializing and exiting.
     */
    @Param(names = {"--init"}, description = "Create the working directory layout and exit")
    public boolean init;

    @Param(names = {"--port", "-p"}, description = "Override the port from configuration.json")
    public String port;

    @Param(names = {"--hostname", "-h"}, description = "Override the hostname from configuration.json")
    public String hostname;

    @Param(names = {"--max-request-size", "-mrs"}, description = "Maximum accepted request size in bytes")
    public String maxRequestSize;

    @Param(
            names = {"--working-directory", "-wd"},
            description = "Directory where Repossify keeps its data")
    public String workingDirectory;
}
