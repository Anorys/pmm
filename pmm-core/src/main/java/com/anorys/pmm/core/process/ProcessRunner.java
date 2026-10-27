package com.anorys.pmm.core.process;

/**
 * Runs external commands ({@code git}, {@code mvn}). Abstracted so that git and Maven logic
 * can be tested with a fake runner.
 */
public interface ProcessRunner {

    /**
     * Runs the process to completion or until its timeout.
     *
     * @throws ProcessException if the executable cannot be found or started
     */
    ProcessResult run(ProcessSpec spec);
}
