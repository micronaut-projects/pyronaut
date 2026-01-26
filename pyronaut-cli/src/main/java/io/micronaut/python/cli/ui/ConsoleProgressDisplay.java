package io.micronaut.python.cli.ui;

public final class ConsoleProgressDisplay implements ProgressDisplay {
    @Override
    public void println(String text) {
        System.out.println(text);
    }

    @Override
    public void setLine(int index, String text) {
        System.out.println(text);
    }
}
