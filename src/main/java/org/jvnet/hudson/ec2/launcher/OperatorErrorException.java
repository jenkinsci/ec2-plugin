package org.jvnet.hudson.ec2.launcher;

/**
 * Signals an error that should be reported to the user without the stack trace.
 *
 * @author Kohsuke Kawaguchi
 */
public class OperatorErrorException extends Exception {
    public OperatorErrorException(String message) {
        super(message);
    }

    public OperatorErrorException(String message, Throwable cause) {
        super(message, cause);
    }

    public OperatorErrorException(Throwable cause) {
        super(cause);
    }
}
