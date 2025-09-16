/*
* The MIT License
*
* Original work from ssh-slaves-plugin Copyright (c) 2016, Michael Clarke
* Modified work Copyright (c) 2020-, M Ramon Leon, CloudBees, Inc.
* Modified work:
* - Just the since annotation

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
import hudson.XmlFile;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.plugins.ec2.util.KeyHelper;
import java.io.File;
import java.io.IOException;
import java.security.PublicKey;
import java.util.Map;
import java.util.WeakHashMap;
import jenkins.model.Jenkins;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;
import org.bouncycastle.crypto.util.PublicKeyFactory;

/**
 * Helper methods to allow loading and saving of host keys for a computer. Verifiers
 * don't have a reference to the Node or Computer that they're running for at the point
 * they're created, so can only load the existing key to run comparisons against at the
 * point the verifier is invoked during the connection attempt.
 * @author Michael Clarke, M Ramon Leon
 * @since TODO
 */
public final class HostKeyHelper {

    private static final HostKeyHelper INSTANCE = new HostKeyHelper();

    private final Map<Computer, HostKey> cache = new WeakHashMap<>();

    private HostKeyHelper() {
        super();
    }

    public static HostKeyHelper getInstance() {
        return INSTANCE;
    }

    /**
     * Converts a Java {@link PublicKey} to a {@link HostKey} for SSH verification.
     * Uses the key's encoded form and BouncyCastle utilities to produce the SSH format.
     *
     * @param serverKey the public key to convert
     * @return a {@link HostKey} representing the SSH-formatted key, or {@code null} if the algorithm is unsupported
     * @throws IOException if the key cannot be processed
     */
    @CheckForNull
    public HostKey getHostKey(@NonNull PublicKey serverKey) throws IOException {
        String sshAlgorithm = KeyHelper.getSshAlgorithm(serverKey);
        if (sshAlgorithm == null) {
            return null;
        }
        AsymmetricKeyParameter parameters = PublicKeyFactory.createKey(serverKey.getEncoded());
        return new HostKey(sshAlgorithm, OpenSSHPublicKeyUtil.encodePublicKey(parameters));
    }

    /**
     * Retrieve the currently trusted host key for the requested computer, or null if
     * no key is currently trusted.
     * @param host the Computer to retrieve the key for.
     * @return the currently trusted key for the requested host, or null if no key is trusted.
     * @throws IOException if the host key can not be read from storage
     */
    public HostKey getHostKey(Computer host) throws IOException {
        HostKey key = cache.get(host);
        if (null == key) {
            File hostKeyFile = getSshHostKeyFile(host.getNode());
            if (hostKeyFile.exists()) {
                XmlFile xmlHostKeyFile = new XmlFile(hostKeyFile);
                key = (HostKey) xmlHostKeyFile.read();
            } else {
                key = null;
            }
            cache.put(host, key);
        }
        return key;
    }

    /**
     * Persists an SSH key to disk for the requested host. This effectively marks
     * the requested key as trusted for all future connections to the host, until
     * any future save attempt replaces this key.
     * @param host the host the key is being saved for
     * @param hostKey the key to be saved as the trusted key for this host
     * @throws IOException on failure saving the key for the host
     */
    public void saveHostKey(Computer host, HostKey hostKey) throws IOException {
        XmlFile xmlHostKeyFile = new XmlFile(getSshHostKeyFile(host.getNode()));
        xmlHostKeyFile.write(hostKey);
        cache.put(host, hostKey);
    }

    private File getSshHostKeyFile(Node node) throws IOException {
        return new File(getNodeDirectory(node), "ssh-host-key.xml");
    }

    private File getNodeDirectory(Node node) throws IOException {
        if (null == node) {
            throw new IOException("Could not load key for the requested node");
        }
        return new File(getNodesDirectory(), node.getNodeName());
    }

    private File getNodesDirectory() throws IOException {
        // Apparent clone of jenkins.model.Nodes#getNodesDirectory(), which no longer even exists.
        File nodesDir = new File(Jenkins.get().getRootDir(), "nodes");
        if (!nodesDir.exists() || !nodesDir.isDirectory()) {
            throw new IOException("Nodes directory does not exist");
        }
        return nodesDir;
    }
}
