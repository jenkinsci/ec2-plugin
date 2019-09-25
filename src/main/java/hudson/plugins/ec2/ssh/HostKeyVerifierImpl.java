/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
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
package hudson.plugins.ec2.ssh;

import java.util.logging.Logger;

import com.trilead.ssh2.ServerHostKeyVerifier;
import java.security.MessageDigest;

public class HostKeyVerifierImpl implements ServerHostKeyVerifier {
    private static final Logger LOGGER = Logger.getLogger(HostKeyVerifierImpl.class.getName());

    private final String console;

    public HostKeyVerifierImpl(String console) {
        this.console = console;
    }

    private String getFingerprint(byte[] serverHostKey) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");

        byte[] fingerprint = md5.digest(serverHostKey);

        StringBuilder buf = new StringBuilder();
        for (byte b : fingerprint) {
            if (buf.length() > 0)
                buf.append(':');
            buf.append(String.format("%02x", b));
        }
        return buf.toString();
    }

    public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey)
            throws Exception {
        String fingerprint = getFingerprint(serverHostKey);

        LOGGER.fine("Host key fingerprint of " + hostname + " is " + fingerprint);

        boolean matches = console.contains(fingerprint);

        if (!matches)
            LOGGER.severe("No matching fingerprint found in the console output: " + console);

        return matches;
    }

}
