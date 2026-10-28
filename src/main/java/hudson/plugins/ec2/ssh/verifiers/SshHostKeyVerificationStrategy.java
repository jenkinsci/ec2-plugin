/*
 * The MIT License
 *
 * Original work from ssh-slaves-plugin Copyright (c) 2016, Michael Clarke
 * Modified work Copyright (c) 2020-, M Ramon Leon, CloudBees, Inc.
 * Modified work:
 * - getHostKeyFromConsole method and called methods
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2.ssh.verifiers;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.InstanceState;
import java.security.PublicKey;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;

/**
 * A method for verifying the host key provided by the remote host during the
 * initiation of each connection.
 *
 * @author Michael Clarke
 * @since TODO
 */
public abstract class SshHostKeyVerificationStrategy implements Describable<SshHostKeyVerificationStrategy> {

    static final String KEYS_BEGIN = "-----BEGIN SSH HOST KEY KEYS-----";
    static final String KEYS_END = "-----END SSH HOST KEY KEYS-----";

    // A getty on the serial console resets the terminal while cloud-init prints the keys, so the console can carry
    // CSI and OSC sequences and other C0 bytes in and around the KEYS block. Tab and newline are kept. An OSC whose
    // terminator was lost stops at the newline, so it cannot swallow the lines after it.
    private static final Pattern CONTROL_SEQUENCES = Pattern.compile(
            "\u001B\\[[0-?]*[ -/]*[@-~]" // CSI
                    + "|\u001B\\][^\u0007\u001B\n]*(?:\u0007|\u001B\\\\)?" // OSC, ended by BEL or ST
                    + "|\u001B[@-_]" // other two-byte escapes
                    + "|[\u0000-\u0008\u000B-\u001F\u007F]");

    @Override
    public SshHostKeyVerificationStrategyDescriptor getDescriptor() {
        return (SshHostKeyVerificationStrategyDescriptor) Jenkins.get().getDescriptorOrDie(getClass());
    }

    /**
     * Check if the given key is valid for the host identifier.
     * @param computer the computer this connection is being initiated for
     * @param hostKey the key that was transmitted by the remote host for the current connection. This is the key
     *                that should be checked to see if we trust it by the current verifier.
     * @param listener the connection listener to write any output log to
     * @return whether the provided HostKey is trusted and the current connection can therefore continue.
     * @since 1.12
     */
    public abstract boolean verify(EC2Computer computer, HostKey hostKey, TaskListener listener) throws Exception;

    /**
     * Check if the given key is valid for the host identifier.
     * @param computer the computer this connection is being initiated for
     * @param serverKey the {@link PublicKey} that was transmitted by the remote host for the current connection. This
     *                  is the key that should be checked to see if we trust it by the current verifier.
     * @param listener the connection listener to write any output log to
     * @return whether the provided {@link PublicKey}  is trusted and the current connection can therefore continue.
     * @since TODO
     */
    public boolean verify(EC2Computer computer, PublicKey serverKey, TaskListener listener) throws Exception {
        return verify(computer, HostKeyHelper.getInstance().getHostKey(serverKey), listener);
    }

    public abstract static class SshHostKeyVerificationStrategyDescriptor
            extends Descriptor<SshHostKeyVerificationStrategy> {}

    /**
     * Get the host key printed out in the console.
     * @param computer
     * @param serverHostKeyAlgorithm
     * @return the hostkey found in the console, null if the console is blank, a non-null HostKey with the algorithm
     * but an empty array as the key if the console is not blank and the key for such an algorithm couldn't be found.
     */
    @Nullable
    HostKey getHostKeyFromConsole(
            @NonNull final Logger logger,
            @NonNull final EC2Computer computer,
            @NonNull final String serverHostKeyAlgorithm) {
        HostKey key;
        TaskListener listener = computer.getListener();

        try {
            if (!computer.getState().equals(InstanceState.RUNNING)) {
                EC2Cloud.log(
                        logger,
                        Level.INFO,
                        listener,
                        "The instance " + computer.getName()
                                + " is not running, waiting to validate the key against the console");
            }
        } catch (InterruptedException e) {
            return null;
        }

        String line = getLineWithKey(logger, computer, serverHostKeyAlgorithm);
        if (line != null && !line.isEmpty()) {
            key = getKeyFromLine(logger, line, listener);
        } else if (line != null) {
            key = new HostKey(serverHostKeyAlgorithm, new byte[] {});
        } else {
            key = null;
        }

        return key;
    }

    /**
     * Get the line with the key for such an algorithm
     * @param logger the logger to print the messages
     * @param computer the computer
     * @param serverHostKeyAlgorithm the algorithm to search for
     * @return the line where the key for the algorithm is on, null if the console is blank, "" if the console is not
     * blank and the line is not found.
     */
    @CheckForNull
    String getLineWithKey(
            @NonNull final Logger logger,
            @NonNull final EC2Computer computer,
            @NonNull final String serverHostKeyAlgorithm) {
        String console = computer.getDecodedConsoleOutput();
        if (console == null) {
            // The instance is running and the console is blank
            EC2Cloud.log(
                    logger,
                    Level.INFO,
                    computer.getListener(),
                    "The instance " + computer.getName()
                            + " has a blank console. Maybe the console is yet not available. If enough time has passed, consider changing the key verification strategy or the AMI used by one printing out the host key in the instance console");
            return null;
        }

        String text = stripControlSequences(console);
        boolean hasKeysBlock = text.contains(KEYS_BEGIN);
        String line = findKeyLine(text, serverHostKeyAlgorithm);
        if (line == null) {
            EC2Cloud.log(
                    logger,
                    Level.INFO,
                    computer.getListener(),
                    String.format(
                            "The instance %s didn't print the host key. Expected a line starting with: \"%s\". %s",
                            computer.getName(),
                            serverHostKeyAlgorithm,
                            hasKeysBlock
                                    ? "The console has a \"" + KEYS_BEGIN
                                            + "\" block but no key for this algorithm in it"
                                    : "The console has no \"" + KEYS_BEGIN + "\" block"));
            return "";
        }
        return line;
    }

    @NonNull
    static String stripControlSequences(@NonNull final String console) {
        return CONTROL_SEQUENCES.matcher(console).replaceAll("");
    }

    /**
     * Find the line holding the key for an algorithm in a console that has already had its control sequences
     * removed. When the console has a KEYS block, only that block is searched, so a mention of the algorithm
     * elsewhere in the boot log cannot be taken for the key. Without a block the whole console is searched.
     * @return the line from the algorithm name to the end of the line, or null if the algorithm is not found.
     */
    @CheckForNull
    static String findKeyLine(@NonNull final String console, @NonNull final String serverHostKeyAlgorithm) {
        String text = console;
        int begin = text.indexOf(KEYS_BEGIN);
        if (begin > -1) {
            int end = text.indexOf(KEYS_END, begin);
            text = text.substring(begin + KEYS_BEGIN.length(), end > -1 ? end : text.length());
        }

        int start = text.indexOf(serverHostKeyAlgorithm);
        if (start < 0) {
            return null;
        }
        int end = text.indexOf('\n', start);
        return text.substring(start, end > -1 ? end : text.length()).trim();
    }

    @CheckForNull
    HostKey getKeyFromLine(
            @NonNull final Logger logger, @NonNull final String line, @Nullable final TaskListener listener) {
        String[] parts = line.split(" ");
        if (parts.length >= 2) {
            // The public SSH key in the console is Base64 encoded
            return new HostKey(parts[0], Base64.getDecoder().decode(parts[1]));
        } else {
            EC2Cloud.log(
                    logger,
                    Level.INFO,
                    listener,
                    String.format(
                            "The line with the key doesn't have the required format. Found: \"%s\". Expected a line with this text: \"ALGORITHM THEHOSTKEY\", example: \"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJbvbEIoY3tqKwkeRW/L1FnbCLLp8a1TwSOyZHKJqFFR \"",
                            line));
            return null;
        }
    }
}
