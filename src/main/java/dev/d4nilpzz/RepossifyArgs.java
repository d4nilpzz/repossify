package dev.d4nilpzz;

import dev.d4nilpzz.params.Param;

public class RepossifyArgs {

    @Param(names = {"--init"}, description = "Initialize Repossify project structure")
    public boolean init;

    @Param(names = {"--port", "-p"}, description = "Override port from configuration")
    public String port;

    @Param(names = {"--hostname", "-h"}, description = "Override hostname from configuration")
    public String hostname;
}
