package hudson.plugins.ec2.win;

import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.security.bc.BCSecurityProvider;
import com.hierynomus.smbj.SmbConfig;
import hudson.plugins.ec2.win.winrm.WinRM;
import hudson.plugins.ec2.win.winrm.WindowsProcess;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.EnumSet;

import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMB2CreateDisposition;

import javax.net.ssl.SSLException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WinConnection {
    private static final Logger LOGGER = Logger.getLogger(WinConnection.class.getName());

    private final String host;
    private final String username;
    private final String password;

    private final SMBClient smbclient;
    private final AuthenticationContext authentication;

    private Connection connection;
    private Session session;

    private boolean useHTTPS;
    private static final int TIMEOUT=8000; //8 seconds
    private boolean allowSelfSignedCertificate;

    @Deprecated
    public WinConnection(String host, String username, String password) {
        this(host, username, password, true);
    }
    
    public WinConnection(String host, String username, String password, boolean allowSelfSignedCertificate) {
        this.host = host;
        this.username = username;
        this.password = password;
        this.smbclient = new SMBClient();
        this.authentication = new AuthenticationContext(username, password.toCharArray(), null);
        this.allowSelfSignedCertificate = allowSelfSignedCertificate;
    }

    public WinRM winrm() {
        WinRM winrm = new WinRM(host, username, password, allowSelfSignedCertificate);
        winrm.setUseHTTPS(useHTTPS);
        return winrm;
    }

    public WinRM winrm(int timeout) {
        WinRM winrm = winrm();
        winrm.setTimeout(timeout);
        return winrm;
    }

    public WindowsProcess execute(String commandLine) {
        return execute(commandLine, 60);
    }

    public WindowsProcess execute(String commandLine, int timeout) {
        return winrm(timeout).execute(commandLine);
    }

    private DiskShare getSmbShare(String path) throws IOException {
        if(this.connection == null) {
            try {
                this.connection = smbclient.connect(host);
            } catch (TransportException e) {
                // JENKINS-66736: unregister and try again
                smbclient.getServerList().unregister(host);
                this.connection = smbclient.connect(host);
            }
        }
        if(this.session == null) {
            this.session = connection.authenticate(this.authentication);
        }
        return (DiskShare) session.connectShare(toAdministrativeShare(path));
    }

    public OutputStream putFile(String path) throws IOException {
        return getSmbShare(path).openFile(toFilePath(path),
                                            EnumSet.of(AccessMask.GENERIC_READ,
                                            AccessMask.GENERIC_WRITE),
                                            null,
                                            SMB2ShareAccess.ALL,
                                            SMB2CreateDisposition.FILE_OVERWRITE_IF,
                                            null).getOutputStream();
    }

    public InputStream getFile(String path) throws IOException {
        return getSmbShare(path).openFile(toFilePath(path),
                                            EnumSet.of(AccessMask.GENERIC_READ),
                                            null, SMB2ShareAccess.ALL,
                                            null,
                                            null).getInputStream();
    }

    public boolean exists(String path) throws IOException {
        return getSmbShare(path).fileExists(toFilePath(path));
    }

    private static String toAdministrativeShare(String path) {
        // administrative windows share are DRIVE$path like
        return path.substring(0, 1) + "$";
    }

    private static String toFilePath(String path) {
        //Strip drive and leading forward slash
        return path.substring(3);
    }

    // keep this method for compatibility, not used in this plugin anymore
    public boolean ping() {
        try {
            return pingFailingIfSSHHandShakeError();
        } catch (IOException ignored) {
            return false;
        }
    }
    
    public boolean pingFailingIfSSHHandShakeError() throws IOException {
        LOGGER.log(Level.FINE, () -> "checking SMB connection to " + host);
        try (
            Socket socket = new Socket();
            Connection connection = smbclient.connect(host);
            Session session = connection.authenticate(authentication);
        ) {
            socket.connect(new InetSocketAddress(host, 445), TIMEOUT);
            winrm().ping();
            session.connectShare("IPC$");
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to verify connectivity to Windows agent", e);
            if (e instanceof SSLException) {
                throw e;
            } else if (e.getCause() instanceof SSLException) {
                throw (SSLException) e.getCause();
            }
            return false;
        }
    }

    public void close() {
        if(this.session != null) {
            try {
                this.session.close();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to close session", e);
            }
        }
        if(this.connection != null) {
            try {
                this.connection.close();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to close connection", e);
            }
        }
        if(this.smbclient != null) {
            try {
                this.smbclient.close();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to close smbclient", e);
            }
        }
    }

    public void setUseHTTPS(boolean useHTTPS) {
        this.useHTTPS = useHTTPS;
    }
}
