package hudson.plugins.ec2.util;

/**
 * Thrown when a Jenkins credential store cannot be found.
 */
public class CredentialStoreNotFoundException extends Exception {
    public CredentialStoreNotFoundException() {
    }

    public CredentialStoreNotFoundException(String s) {
        super(s);
    }

    public CredentialStoreNotFoundException(String s, Throwable throwable) {
        super(s, throwable);
    }

    public CredentialStoreNotFoundException(Throwable throwable) {
        super(throwable);
    }

    public CredentialStoreNotFoundException(String s, Throwable throwable, boolean b, boolean b1) {
        super(s, throwable, b, b1);
    }
}
