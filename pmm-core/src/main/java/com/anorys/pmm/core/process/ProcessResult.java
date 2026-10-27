package com.anorys.pmm.core.process;

import java.time.Duration;
import java.util.List;

/** Outcome of a finished (or killed) process. Standard error is merged into {@link #output()}. */
public record ProcessResult(int exitCode, String output, Duration duration, boolean timedOut) {

    public boolean succeeded() {
        return !timedOut && exitCode == 0;
    }

    public List<String> lines() {
        return output.lines().toList();
    }
}
