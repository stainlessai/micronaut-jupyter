package ai.stainless.micronaut.jupyter.kernel;

public class KernelExitException extends Exception {

    public KernelExitException (String msg) {
        super(msg);
    }

    public KernelExitException(String msg, Throwable cause) {
        super(msg, cause);
    }

}
