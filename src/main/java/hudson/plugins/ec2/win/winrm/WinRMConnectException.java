package hudson.plugins.ec2.win.winrm;

class WinRMConnectException extends RuntimeIOException {

    WinRMConnectException(String message, Throwable cause) {
        super(message, cause);
    }
}
