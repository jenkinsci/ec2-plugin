package hudson.plugins.ec2.win.winrm;

import com.google.common.io.Closeables;
import hudson.remoting.FastPipedInputStream;
import hudson.remoting.FastPipedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WindowsProcess {
    private static final Logger log = Logger.getLogger(WindowsProcess.class.getName());

    private final static int INPUT_BUFFER = 16 * 1024;
    private final WinRMClient client;

    private final FastPipedInputStream toCallersStdin;
    private final FastPipedOutputStream callersStdin;
    private final FastPipedInputStream callersStdout;
    private final FastPipedOutputStream toCallersStdout;
    private final FastPipedInputStream callersStderr;
    private final FastPipedOutputStream toCallersStderr;

    private boolean terminated;
    private final String command;

    private Thread outputThread;

    private Thread inputThread;

    WindowsProcess(WinRMClient client, String command) throws IOException {
        this.client = client;
        this.command = command;

        toCallersStdin = new FastPipedInputStream();
        callersStdin = new FastPipedOutputStream(toCallersStdin);
        callersStdout = new FastPipedInputStream();
        toCallersStdout = new FastPipedOutputStream(callersStdout);
        callersStderr = new FastPipedInputStream();
        toCallersStderr = new FastPipedOutputStream(callersStderr);

        startStdoutCopyThread();
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        startStdinCopyThread();
    }

    public InputStream getStdout() {
        return callersStdout;
    }

    public OutputStream getStdin() {
        return callersStdin;
    }

    public InputStream getStderr() {
        return callersStderr;
    }

    public synchronized int waitFor() {
        if (terminated) {
            return client.exitCode();
        }

        try {
            try {
                outputThread.join();
            } finally {
                client.deleteShell();
                terminated = true;
            }
            return client.exitCode();
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Exception while executing command", exc);
        }
    }

    public synchronized void destroy() {
        if (terminated) {
            return;
        }

        client.signal();
        client.deleteShell();
        terminated = true;
    }

    private void startStdoutCopyThread() {
        outputThread = new Thread("output copy: " + command) {
            @Override
            public void run() {
                try {
                    for (;;) {
                        if (!client.slurpOutput(toCallersStdout, toCallersStderr)) {
                            log.log(Level.FINE, "no more output for " + command);
                            break;
                        }
                    }
                } catch (Exception exc) {
                    log.log(Level.WARNING, "ouch, stdout exception for " + command, exc);
                    exc.printStackTrace();
                } finally {
                    Closeables.closeQuietly(toCallersStdout);
                    Closeables.closeQuietly(toCallersStderr);
                }
            }
        };
        outputThread.setDaemon(true);
        outputThread.start();
    }

    private void startStdinCopyThread() {
        inputThread = new Thread("input copy: " + command) {
            @Override
            public void run() {
                try {
                    byte[] buf = new byte[INPUT_BUFFER];
                    for (;;) {
                        int n = toCallersStdin.read(buf);

                        if (n == -1)
                            break;
                        if (n == 0)
                            continue;

                        byte[] bufToSend = new byte[n];
                        System.arraycopy(buf, 0, bufToSend, 0, n);
                        log.log(Level.FINE, "piping " + bufToSend.length + " to input of " + command);
                        client.sendInput(bufToSend);
                    }
                } catch (Exception exc) {
                    log.log(Level.WARNING, "ouch, STDIN exception for " + command, exc);
                } finally {
                    Closeables.closeQuietly(callersStdin);
                }
            }
        };
        inputThread.setDaemon(true);
        inputThread.start();
    }
}
