package com.anorys.pmm.maven.nexus;

/** The Maven repository could not be queried: unreachable, refused the credentials, or answered garbage. */
public class NexusException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NexusException(String message) {
        super(message);
    }

    public NexusException(String message, Throwable cause) {
        super(message, cause);
    }
}
