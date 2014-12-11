package hudson.plugins.ec2.win.winrm.request;

import java.net.URL;

public class RequestFactory {
    private URL url;
    private String timeout = "PT60S";
    private int envelopSize = 153600;
    private String locale = "en-US";

    public RequestFactory(URL url) {
        this.url = url;
    }

    public OpenShellRequest newOpenShellRequest() {
        OpenShellRequest r = new OpenShellRequest(url);
        setDefaults(r);
        return r;
    }

    public ExecuteCommandRequest newExecuteCommandRequest(String shellId, String command) {
        ExecuteCommandRequest r = new ExecuteCommandRequest(url, shellId, command);
        setDefaults(r);
        return r;
    }

    public DeleteShellRequest newDeleteShellRequest(String shellId) {
        DeleteShellRequest r = new DeleteShellRequest(url, shellId);
        setDefaults(r);
        return r;
    }

    public SignalRequest newSignalRequest(String shellId, String commandId) {
        SignalRequest r = new SignalRequest(url, shellId, commandId);
        setDefaults(r);
        return r;
    }

    public SendInputRequest newSendInputRequest(byte[] input, String shellId, String commandId) {
        SendInputRequest r = new SendInputRequest(url, input, shellId, commandId);
        setDefaults(r);
        return r;
    }

    public GetOutputRequest newGetOutputRequest(String shellId, String commandId) {
        GetOutputRequest r = new GetOutputRequest(url, shellId, commandId);
        setDefaults(r);
        return r;
    }

    private void setDefaults(AbstractWinRMRequest r) {
        r.setTimeout(timeout);
        r.setLocale(locale);
        r.setEnvelopSize(envelopSize);
    }

    public String getTimeout() {
        return timeout;
    }

    public void setTimeout(String timeout) {
        this.timeout = timeout;
    }

    public int getEnvelopSize() {
        return envelopSize;
    }

    public void setEnvelopSize(int envelopSize) {
        this.envelopSize = envelopSize;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

}
