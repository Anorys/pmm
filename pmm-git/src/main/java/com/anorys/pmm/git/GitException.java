package com.anorys.pmm.git;

/** A git command failed. The message includes git's own output. */
public class GitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GitException(String message) {
        super(message);
    }
}
