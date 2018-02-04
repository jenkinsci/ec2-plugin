package hudson.plugins.ec2.win.winrm;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

public class WinRM {

    private final String host;
    private final String username;
    private final String password;

    private int timeout = 60;

    private boolean useHTTPS;

    public WinRM(String host, String username, String password) {
        this.host = host;
        this.username = username;
        this.password = password;
    }

    public void ping() {
        WinRMClient client = new WinRMClient(buildURL(), username, password);
        client.setTimeout(secToDuration(timeout));
        client.setUseHTTPS(isUseHTTPS());
        try {
            client.openShell();
        } finally {
            try {
                client.deleteShell();
            } catch (Exception e) {
                // No-op
            }
        }
    }

    public WindowsProcess execute(String commandLine) {
        WinRMClient client = new WinRMClient(buildURL(), username, password);
        client.setTimeout(secToDuration(timeout));
        client.setUseHTTPS(isUseHTTPS());
        try {
            client.openShell();
            client.executeCommand(commandLine);

            return new WindowsProcess(client, commandLine);
        } catch (IOException exc) {
            throw new RuntimeException("Cannot execute command " + commandLine + " on " + this, exc);
        }
    }

    private URL buildURL() {
        String scheme = useHTTPS ? "https" : "http";
        int port = useHTTPS ? 5986 : 5985;

        try {
            return new URL(scheme, host, port, "/wsman");
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid winrm url");
        }
    }

    private boolean isUseHTTPS() {
        return useHTTPS;
    }

    public void setUseHTTPS(boolean useHTTPS) {
        this.useHTTPS = useHTTPS;
    }

    public void setTimeout(int seconds) {
        this.timeout = seconds;
    }

    /**
     * # Convert the number of seconds to an ISO8601 duration format # @see
     * http://tools.ietf.org/html/rfc2445#section-4.3.6 # @param [Fixnum] seconds The amount of seconds for this
     * duration
     */
    private static String secToDuration(int seconds) {
        StringBuilder iso = new StringBuilder("P");
        if (seconds > 604800) {
            // more than a week
            int weeks = seconds / 604800;
            seconds -= (604800 * weeks);
            iso.append(weeks).append('W');
        }

        if (seconds > 86400) {
            // more than a day
            int days = seconds / 86400;
            seconds -= (86400 * days);
            iso.append(days).append('D');
        }

        if (seconds > 0) {
            iso.append('T');
            if (seconds > 3600) { // more than an hour
                int hours = seconds / 3600;
                seconds -= (3600 * hours);
                iso.append(hours).append('H');
            }
            if (seconds > 60) { // more than a minute
                int minutes = seconds / 60;
                seconds -= (60 * minutes);
                iso.append(minutes).append('M');
            }
            iso.append(seconds).append('S');
        }

        return iso.toString();
    }
}
