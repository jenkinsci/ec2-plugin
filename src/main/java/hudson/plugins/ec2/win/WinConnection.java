package hudson.plugins.ec2.win;

import hudson.plugins.ec2.win.winrm.WinRM;
import hudson.plugins.ec2.win.winrm.WindowsProcess;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.regex.Pattern;
import java.util.EnumSet;

import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMB2CreateDisposition;

import static java.util.regex.Pattern.quote;

import java.util.logging.Level;
import java.util.logging.Logger;

public class WinConnection {
    private static final Logger log = Logger.getLogger(WinConnection.class.getName());

    // ^[a-zA-Z]\:(\\|\/)([^\\\/\:\*\?\<\>\"\|]+(\\|\/){0,1})+$
    private static final Pattern VALIDATE_WINDOWS_PATH = Pattern.compile("^[A-Za-z]:\\\\[-a-zA-Z0-9_.\\\\]*");

    private final String host;
    private final String username;
    private final String password;

    private final SMBClient smbclient;
    private final AuthenticationContext authentication;

    private boolean useHTTPS;
    private static final int TIMEOUT=8000; //8 seconds

    public WinConnection(String host, String username, String password) {
        this.host = host;
        this.username = username;
        this.password = password;
        this.smbclient = new SMBClient();
        this.authentication = new AuthenticationContext(username, password.toCharArray(), null);
    }

    public WinRM winrm() {
        WinRM winrm = new WinRM(host, username, password);
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
        Connection connection = smbclient.connect(host);
        Session session = connection.authenticate(authentication);
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

    public boolean ping() {
        log.log(Level.FINE, "checking SMB connection to " + host);
        try {
            Socket socket=new Socket();
            socket.connect(new InetSocketAddress(host, 445), TIMEOUT);
            socket.close();
            winrm().ping();
            Connection connection = smbclient.connect(host);
            Session session = connection.authenticate(authentication);
            session.connectShare("IPC$");
            return true;
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to verify connectivity to Windows slave", e);
            return false;
        }
    }

    public void close() {
    }

    public void setUseHTTPS(boolean useHTTPS) {
        this.useHTTPS = useHTTPS;
    }
}
