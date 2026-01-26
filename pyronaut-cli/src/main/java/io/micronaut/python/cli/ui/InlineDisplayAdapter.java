package io.micronaut.python.cli.ui;

import dev.tamboui.inline.InlineDisplay;

public final class InlineDisplayAdapter implements ProgressDisplay {
    private final InlineDisplay display;

    public InlineDisplayAdapter(InlineDisplay display) {
        this.display = display;
    }

    @Override
    public void println(String text) {
        display.println(text);
    }

    @Override
    public void setLine(int index, String text) {
        display.setLine(index, text);
    }

    @Override
    public void close() {
        try {
            display.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
