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
package hudson.plugins.ec2;

import hudson.util.Secret;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.security.DigestInputStream;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;

import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.openssl.PEMReader;
import org.bouncycastle.openssl.PasswordFinder;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.KeyPairInfo;

/**
 * RSA private key (the one that you generate with ec2-add-keypair.)
 *
 * Starts with "----- BEGIN RSA PRIVATE KEY------\n".
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2PrivateKey {
    private final Secret privateKey;

    EC2PrivateKey(String privateKey) {
        this.privateKey = Secret.fromString(privateKey.trim());
    }

    public String getPrivateKey() {
        return privateKey.getPlainText();
    }

    /**
     * Obtains the fingerprint of the key in the "ab:cd:ef:...:12" format.
     */
    /**
     * Obtains the fingerprint of the key in the "ab:cd:ef:...:12" format.
     */
    public String getFingerprint() throws IOException {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        Reader r = new BufferedReader(new StringReader(privateKey.getPlainText()));
        PEMReader pem = new PEMReader(r, new PasswordFinder() {
            public char[] getPassword() {
                throw PRIVATE_KEY_WITH_PASSWORD;
            }
        });

        try {
            KeyPair pair = (KeyPair) pem.readObject();
            if (pair == null)
                return null;
            PrivateKey key = pair.getPrivate();
            return digest(key);
        } catch (RuntimeException e) {
            if (e == PRIVATE_KEY_WITH_PASSWORD)
                throw new IOException("This private key is password protected, which isn't supported yet");
            throw e;
        } finally {
            pem.close();
        }
    }

    public String getPublicFingerprint() throws IOException {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        Reader r = new BufferedReader(new StringReader(privateKey.getPlainText()));
        PEMReader pem = new PEMReader(r, new PasswordFinder() {
            public char[] getPassword() {
                throw PRIVATE_KEY_WITH_PASSWORD;
            }
        });

        try {
            KeyPair pair = (KeyPair) pem.readObject();
            if (pair == null)
                return null;
            PublicKey key = pair.getPublic();
            return digestOpt(key, "MD5");
        } catch (RuntimeException e) {
            if (e == PRIVATE_KEY_WITH_PASSWORD)
                throw new IOException("This private key is password protected, which isn't supported yet");
            throw e;
        } finally {
            pem.close();
        }
    }

    /**
     * Is this file really a private key?
     */
    public boolean isPrivateKey() throws IOException {
        BufferedReader br = new BufferedReader(new StringReader(privateKey.getPlainText()));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.equals("-----BEGIN RSA PRIVATE KEY-----"))
                return true;
        }
        return false;
    }

    /**
     * Finds the {@link KeyPairInfo} that corresponds to this key in EC2.
     */
    public com.amazonaws.services.ec2.model.KeyPair find(AmazonEC2 ec2) throws IOException, AmazonClientException {
        String fp = getFingerprint();
        String pfp = getPublicFingerprint();
        for (KeyPairInfo kp : ec2.describeKeyPairs().getKeyPairs()) {
            if (kp.getKeyFingerprint().equalsIgnoreCase(fp)) {
                com.amazonaws.services.ec2.model.KeyPair keyPair = new com.amazonaws.services.ec2.model.KeyPair();
                keyPair.setKeyName(kp.getKeyName());
                keyPair.setKeyFingerprint(fp);
                keyPair.setKeyMaterial(Secret.toString(privateKey));
                return keyPair;
            }
            if (kp.getKeyFingerprint().equalsIgnoreCase(pfp)) {
                com.amazonaws.services.ec2.model.KeyPair keyPair = new com.amazonaws.services.ec2.model.KeyPair();
                keyPair.setKeyName(kp.getKeyName());
                keyPair.setKeyFingerprint(pfp);
                keyPair.setKeyMaterial(Secret.toString(privateKey));
                return keyPair;
            }
        }
        return null;
    }

    @Override
    public int hashCode() {
        return privateKey.hashCode();
    }

    @Override
    public boolean equals(Object that) {
        return that instanceof EC2PrivateKey && this.privateKey.equals(((EC2PrivateKey) that).privateKey);
    }

    @Override
    public String toString() {
        return privateKey.getPlainText();
    }

    /* package */static String digest(PrivateKey k) throws IOException {
        return digestOpt(k, "SHA1");
    }

    /* package */static String digestOpt(Key k, String dg) throws IOException {
        try {
            MessageDigest md5 = MessageDigest.getInstance(dg);

            DigestInputStream in = new DigestInputStream(new ByteArrayInputStream(k.getEncoded()), md5);
            try {
                while (in.read(new byte[128]) > 0)
                    ; // simply discard the input
            } finally {
                in.close();
            }
            StringBuilder buf = new StringBuilder();
            char[] hex = Hex.encodeHex(md5.digest());
            for (int i = 0; i < hex.length; i += 2) {
                if (buf.length() > 0)
                    buf.append(':');
                buf.append(hex, i, 2);
            }
            return buf.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static final RuntimeException PRIVATE_KEY_WITH_PASSWORD = new RuntimeException();
}
