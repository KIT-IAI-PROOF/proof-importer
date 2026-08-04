/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */

package edu.kit.iai.webis.proofimporter;

public class PojoCreationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

	public PojoCreationException(String message, Throwable cause) {
        super(message, cause);
    }

    public PojoCreationException(String message) {
        super(message);
    }

    public PojoCreationException(Throwable cause) {
        super(cause);
    }
}
