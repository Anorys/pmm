package com.anorys.pmm.tui.theme;

import com.anorys.pmm.core.Severity;

/** The state families of the style guide, each with one color and one symbol. */
public enum Tone {
    OK, WARN, ERROR, BLOCKED, INFO, RUNNING, IDLE;

    public static Tone of(Severity severity) {
        return switch (severity) {
            case ERROR -> ERROR;
            case WARN -> WARN;
            case INFO -> INFO;
            case OFF -> IDLE;
        };
    }
}
