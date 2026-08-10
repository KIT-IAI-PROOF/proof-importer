/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */

package edu.kit.iai.webis.proofimporter;

public class WrongKeywordException extends RuntimeException {

    private static final long serialVersionUID = 1L;

	public WrongKeywordException(String message, Throwable cause) {
        super(message, cause);
    }

    public WrongKeywordException(String message) {
        super(message);
    }

    public WrongKeywordException(Throwable cause) {
        super(cause);
    }
}
