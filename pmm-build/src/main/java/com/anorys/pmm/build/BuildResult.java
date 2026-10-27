package com.anorys.pmm.build;

import java.nio.file.Path;
import java.time.Duration;

/**
 * The outcome of building one repository.
 *
 * @param log     the full Maven output, or {@code null} when Maven did not run
 * @param failure why it failed, for {@link Status#FAILED} and {@link Status#BLOCKED}
 * @param reason  why it was not built, for {@link Status#SKIPPED}
 */
public record BuildResult(String repository, Status status, Duration duration, Path log, Failure failure,
                          String reason) {

    public enum Status {
        SUCCESS, CACHED, FAILED, BLOCKED, SKIPPED;

        public boolean ok() {
            return this == SUCCESS || this == CACHED;
        }
    }
}
