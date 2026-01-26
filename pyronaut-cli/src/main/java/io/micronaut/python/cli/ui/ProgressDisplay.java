package io.micronaut.python.cli.ui;

public interface ProgressDisplay extends AutoCloseable {
    void println(String text);
    void setLine(int index, String text);
    @Override
    default void close() {
    }
}
