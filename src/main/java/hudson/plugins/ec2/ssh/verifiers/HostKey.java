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

import com.trilead.ssh2.KnownHosts;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.io.Serializable;
import java.util.Arrays;

/**
 * A representation of the SSH key provided by a remote host to verify itself
 * and secure the initial setup of the SSH connection.
 * @author Michael Clarke, M Ramon Leon
 * @since TODO
 */
public final class HostKey implements Serializable {

    private static final long serialVersionUID = -3873284593211178494L;

    private final String algorithm;
    private final byte[] key;

    public HostKey(@NonNull String algorithm, @NonNull byte[] key) {
        super();
        this.algorithm = algorithm;
        this.key = key.clone();
    }

    /**
     * Get the algorithm used during key generation.
     * @return the algorithm used to generate the key, such as ssh-rsa.
     */
    @NonNull
    public String getAlgorithm() {
        return algorithm;
    }

    /**
     * Get the unencoded content of the key, without any algorithm prefix.
     * @return a byte representation of the raw key value.
     */
    @NonNull
    public byte[] getKey() {
        return key.clone();
    }

    public String getFingerprint() {
        return KnownHosts.createHexFingerprint(getAlgorithm(), getKey());
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((algorithm == null) ? 0 : algorithm.hashCode());
        result = prime * result + Arrays.hashCode(key);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        HostKey other = (HostKey) obj;
        if (algorithm == null) {
            if (other.algorithm != null)
                return false;
        } else if (!algorithm.equals(other.algorithm))
            return false;
        if (!Arrays.equals(key, other.key))
            return false;
        return true;
    }
}
