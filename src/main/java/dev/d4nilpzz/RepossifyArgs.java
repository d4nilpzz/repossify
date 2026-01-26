package dev.d4nilpzz;

import dev.d4nilpzz.params.Param;

public class RepossifyArgs {

    @Param(names = {"--help"}, description = "Show github issues link")
    public boolean help;

    @Param(names = {"--version"}, description = "Prints the version of Repossify")
    public boolean version;

    @Param(names = {"--port", "-p"}, description = "Override port from configuration")
    public String port;

    @Param(names = {"--hostname", "-h"}, description = "Override hostname from configuration")
    public String hostname;

    @Param(names = {"--max-request-size", "-mrs"}, description = "The maximum size of a request")
    public String maxRequestSize;

    @Param(
            names = {"--working-directory", "-wd"},
            description = "Sets custom working directory for this instance, so the location where Repossify keeps local data")
    public String workingDirectory;
}
