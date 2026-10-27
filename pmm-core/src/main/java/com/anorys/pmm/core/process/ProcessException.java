package com.anorys.pmm.core.process;

/** The process could not be started, or waiting for it was interrupted. */
public class ProcessException extends RuntimeException {

    public ProcessException(String message) {
        super(message);
    }

    public ProcessException(String message, Throwable cause) {
        super(message, cause);
    }
}
