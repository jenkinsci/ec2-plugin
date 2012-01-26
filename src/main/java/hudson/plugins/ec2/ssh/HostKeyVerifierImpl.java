package hudson.plugins.ec2.ssh;

import java.util.logging.Logger;

import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.crypto.digest.MD5;

/**
 * {@link ServerHostKeyVerifier} that makes sure that the host key fingerprint
 * showed up in {@link ConsoleOutput#getOutput()}.
 * 
 * @author Kohsuke Kawaguchi
 */
public class HostKeyVerifierImpl implements ServerHostKeyVerifier {
    private final String console;

    public HostKeyVerifierImpl(String console) {
        this.console = console;
    }

    private String getFingerprint(byte[] serverHostKey) {
        MD5 md5 = new MD5();
        md5.update(serverHostKey);

        byte[] fingerprint = new byte[16];

        md5.digest(fingerprint);
        StringBuilder buf = new StringBuilder();
        for( byte b : fingerprint ) {
            if(buf.length()>0)  buf.append(':');
            buf.append(String.format("%02x",b));
        }
        return buf.toString();
    }

    public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) throws Exception {
        String fingerprint = getFingerprint(serverHostKey);

        LOGGER.fine("Host key fingerprint of "+hostname+" is "+fingerprint);

        boolean matches = console.contains(fingerprint);

        if(!matches)
            LOGGER.severe("No matching fingerprint found in the console output: "+console);

        return matches;
    }

    private static final Logger LOGGER = Logger.getLogger(HostKeyVerifierImpl.class.getName());
}
