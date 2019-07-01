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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.security.UnrecoverableKeyException;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.KeyPairInfo;

import hudson.util.Secret;
import jenkins.bouncycastle.api.PEMEncodable;
import org.bouncycastle.util.encoders.Base64;
import javax.crypto.Cipher;
import java.nio.charset.Charset;
import com.amazonaws.AmazonClientException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

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

    @SuppressWarnings("unused") // used by config-entries.jelly
    @Restricted(NoExternalUse.class)
    public Secret getPrivateKeySecret() {
        return privateKey;
    }

    /**
     * Obtains the fingerprint of the key in the "ab:cd:ef:...:12" format.
     *
     * @throws IOException if the underlying private key is invalid: empty or password protected
     *    (password protected private keys are not yet supported)
     */
    public String getFingerprint() throws IOException {
        String pemData = privateKey.getPlainText();
        if (pemData == null || pemData.isEmpty()) {
            throw new IOException("This private key cannot be empty");
        }
        try {
            return PEMEncodable.decode(pemData).getPrivateKeyFingerprint();
        } catch (UnrecoverableKeyException e) {
            throw new IOException("This private key is password protected, which isn't supported yet");
        }
    }

    public String getPublicFingerprint() throws IOException {
        try {
            return PEMEncodable.decode(privateKey.getPlainText()).getPublicKeyFingerprint();
        } catch (UnrecoverableKeyException e) {
            throw new IOException("This private key is password protected, which isn't supported yet");
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

    public String decryptWindowsPassword(String encodedPassword) throws AmazonClientException {
        try {
            Cipher cipher = Cipher.getInstance("RSA/NONE/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, PEMEncodable.decode(privateKey.getPlainText()).toPrivateKey());
            byte[] cipherText = Base64.decode(encodedPassword);
            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, Charset.forName("ASCII"));
        } catch (Exception e) {
            throw new AmazonClientException("Unable to decode password:\n" + e.toString());
        }
    }

    @Override
    public int hashCode() {
        return privateKey.hashCode();
    }

    @Override
    public boolean equals(Object that) {
        if (that != null && this.getClass() == that.getClass())
            return this.privateKey.equals(((EC2PrivateKey) that).privateKey);
        return false;
    }

    @Override
    public String toString() {
        return privateKey.getPlainText();
    }
}
