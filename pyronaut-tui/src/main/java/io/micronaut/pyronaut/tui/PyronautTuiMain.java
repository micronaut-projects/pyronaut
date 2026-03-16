package io.micronaut.pyronaut.tui;

import io.micronaut.pyronaut.tui.commands.PyronautDelegatingTuiCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(
        name = "pyronaut-tui",
        description = "Pyronaut Tamboui TUI",
        subcommands = {PyronautDelegatingTuiCommand.class},
        mixinStandardHelpOptions = true
)
public final class PyronautTuiMain implements Callable<Integer> {
    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(String[] args) {
        var exitCode = new CommandLine(new PyronautTuiMain()).execute(args);
        System.exit(exitCode);
    }
}
