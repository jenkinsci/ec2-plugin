package hudson.plugins.ec2.win.winrm.request;

import hudson.plugins.ec2.win.winrm.soap.Namespaces;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;

public class SignalRequest extends AbstractWinRMRequest {

    private String commandId, shellId;

    public SignalRequest(URL url, String shellId, String commandId) {
        super(url);
        this.commandId = commandId;
        this.shellId = shellId;
    }

    @Override
    protected void construct() {
        try {
            defaultHeader().action(new URI("http://schemas.microsoft.com/wbem/wsman/1/windows/shell/Command")).resourceURI(new URI("http://schemas.microsoft.com/wbem/wsman/1/windows/shell/cmd")).shellId(shellId);

            Element body = DocumentHelper.createElement(QName.get("Signal", Namespaces.NS_WIN_SHELL)).addAttribute("CommandId", commandId);

            body.addElement(QName.get("Code", Namespaces.NS_WIN_SHELL)).addText("http://schemas.microsoft.com/wbem/wsman/1/windows/shell/signal/terminate");
            setBody(body);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Error while building request content", e);
        }
    }

}
