package io.micronaut.configuration.jupyter.kernel;

public class UnexpectedExitException extends SecurityException {

    public UnexpectedExitException (String msg) {
        super(msg);
    }

    public UnexpectedExitException(String msg, Throwable cause) {
        super(msg, cause);
    }

}
