package hudson.plugins.ec2.win;

import static java.util.regex.Pattern.quote;

import hudson.plugins.ec2.win.winrm.WinRM;
import hudson.plugins.ec2.win.winrm.WindowsProcess;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbFile;
import jcifs.util.LogStream;

public class WinConnection {

    private static final Logger log = Logger.getLogger(WinConnection.class.getName());

    private static final Pattern VALIDATE_WINDOWS_PATH = Pattern.compile("^[A-Za-z]:\\\\[-a-zA-Z0-9_.\\\\]*");
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;

    private final String host;
    private final String username;
    private final String password;

    private final NtlmPasswordAuthentication authentication;

    private boolean useHTTPS;

    WinConnection(String host, String username, String password) {
        this.host = host;
        this.username = username;
        this.password = password;
        this.authentication = new NtlmPasswordAuthentication(null, username, password);
        if (log.isLoggable(Level.FINE)) {
            LogStream.level = 6;
        }
    }

    private WinRM winrm() {
        WinRM winrm = new WinRM(host, username, password);
        winrm.setUseHTTPS(useHTTPS);
        return winrm;
    }

    private WinRM winrm(int timeoutInSeconds) {
        WinRM winrm = winrm();
        winrm.setTimeout(timeoutInSeconds);
        return winrm;
    }

    public WindowsProcess execute(String commandLine) {
        return execute(commandLine, DEFAULT_COMMAND_TIMEOUT_SECONDS);
    }

    public WindowsProcess execute(String commandLine, int timeout) {
        return winrm(timeout).execute(commandLine);
    }

    public OutputStream putFile(String path) throws IOException {
        SmbFile smbFile = new SmbFile(encodeForSmb(path), authentication);
        return smbFile.getOutputStream();
    }

    public boolean exists(String path) throws IOException {
        SmbFile smbFile = new SmbFile(encodeForSmb(path), authentication);
        return smbFile.exists();
    }

    private String encodeForSmb(String path) {
        if (!VALIDATE_WINDOWS_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Path '" + path + "' is not a valid windows path like C:\\Windows\\Temp");
        }

        return smbURLPrefix() + toAdministrativeSharePath(path).replace('\\', '/');
    }

    private static String toAdministrativeSharePath(String path) {
        // administrative windows share are DRIVE$path like
        return path.substring(0, 1) + "$" + path.substring(2);
    }

    private String smbURLPrefix() {
        StringBuilder prefix = new StringBuilder();
        prefix.append("smb://");
        if (username != null) {
            prefix.append(urlEncode(username.replaceFirst(quote("\\"), ";")))
                    .append(':')
                    .append(urlEncode(password))
                    .append('@');
        }
        // port?
        prefix.append(urlEncode(host));
        prefix.append('/');
        return prefix.toString();
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Invalid SMB URL", e);
        }
    }

    public boolean ping() {
        log.log(Level.FINE, "pinging " + host);
        try {
            winrm().ping();
            SmbFile test = new SmbFile(smbURLPrefix() + "IPC$", authentication);
            test.connect();
            return true;
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to ping Windows slave", e);
            return false;
        }
    }

    public void setUseHTTPS(boolean useHTTPS) {
        this.useHTTPS = useHTTPS;
    }
}
