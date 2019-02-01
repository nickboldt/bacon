package org.jboss.pnc.bacon.cli.pnc;

import org.jboss.pnc.bacon.cli.SubCommandHelper;
import picocli.CommandLine;

@CommandLine.Command(name = "repository", mixinStandardHelpOptions = true)
public class Repository extends SubCommandHelper {

    @CommandLine.Command(name = "create", mixinStandardHelpOptions = true)
    public void create() {
    }

    @CommandLine.Command(name = "get", mixinStandardHelpOptions = true)
    public void get() {
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true)
    public void list() {
    }

    @CommandLine.Command(name = "update", mixinStandardHelpOptions = true)
    public void update() {
    }

    @CommandLine.Command(name = "delete", mixinStandardHelpOptions = true)
    public void delete() {
    }
}
