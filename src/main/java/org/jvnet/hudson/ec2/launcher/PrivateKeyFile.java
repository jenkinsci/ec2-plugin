package org.jvnet.hudson.ec2.launcher;

import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.openssl.PEMReader;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.security.DigestInputStream;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Security;

/**
 * Represents the RSA private key that EC2 uses.
 *
 * @author Kohsuke Kawaguchi
 */
class PrivateKeyFile {
    public final File file;

    public PrivateKeyFile(File file) {
        this.file = file;
    }

    /**
     * Obtains the fingerprint of the key in the "ab:cd:ef:...:12" format.
     */
    public String getFingerprint() throws IOException {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        Reader r = new BufferedReader(new FileReader(file));
        try {
            PEMReader pem = new PEMReader(r);
            PrivateKey key = ((KeyPair) pem.readObject()).getPrivate();
            return digest(key);
        } finally {
            r.close();
        }
    }

    /**
     * Is this file really a private key?
     */
    public boolean isPrivateKey() throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(file));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.equals("-----BEGIN RSA PRIVATE KEY-----"))
                    return true;
            }
            return false;
        } finally {
            br.close();
        }
    }

    private static String digest(PrivateKey k) throws IOException {
        try {
            MessageDigest md5 = MessageDigest.getInstance("SHA1");

            DigestInputStream in = new DigestInputStream(new ByteArrayInputStream(k.getEncoded()), md5);
            try {
                while (in.read(new byte[128]) > 0)
                    ; // simply discard the input
            } finally {
                in.close();
            }
            StringBuilder buf = new StringBuilder();
            char[] hex = Hex.encodeHex(md5.digest());
            for( int i=0; i<hex.length; i+=2 ) {
                if(buf.length()>0)  buf.append(':');
                buf.append(hex,i,2);
            }
            return buf.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
