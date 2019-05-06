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
        this.authentication = new AuthenticationContext(username, password.toCharArray(), null);;
        if(log.isLoggable(Level.FINE)){
            setLogStreamLevel(6);
        }
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

    public OutputStream putFile(String path) throws IOException {
        Connection connection = smbclient.connect(host);
        Session session = connection.authenticate(authentication);
        DiskShare share = (DiskShare) session.connectShare(toAdministrativeShare(path));
        String filepath = path.substring(2);
        return share.openFile(filepath, EnumSet.of(AccessMask.GENERIC_READ), null, EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), null, null).getOutputStream();
    }

    public InputStream getFile(String path) throws IOException {
        Connection connection = smbclient.connect(host);
        Session session = connection.authenticate(authentication);
        DiskShare share = (DiskShare) session.connectShare(toAdministrativeShare(path));
        String filepath = path.substring(2);
        return share.openFile(filepath, EnumSet.of(AccessMask.GENERIC_READ), null, EnumSet.of(SMB2ShareAccess.FILE_SHARE_WRITE), null, null).getInputStream();
    }

    public boolean exists(String path) throws IOException {
        Connection connection = smbclient.connect(host);
        Session session = connection.authenticate(authentication);
        DiskShare share = (DiskShare) session.connectShare(toAdministrativeShare(path));
        String filepath = path.substring(2);
        return share.fileExists(filepath);
    }
   
    private String encodeForSmb(String path) {
        if (!VALIDATE_WINDOWS_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Path '" + path + "' is not a valid windows path like C:\\Windows\\Temp");
        }

        StringBuilder smbUrl = new StringBuilder(smbURLPrefix());
        smbUrl.append(toAdministrativeSharePath(path).replace('\\', '/'));
        return smbUrl.toString();
    }

    private static String toAdministrativeSharePath(String path) {
        // administrative windows share are DRIVE$path like
        return path.substring(0, 1) + "$";
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Invalid SMB URL", e);
        }
    }

    private static void setLogStreamLevel(int level) {
        LogStream.level = level;
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
            session.connectShare("IPC$");;
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
