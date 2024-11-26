package hudson.plugins.ec2.win.winrm.request;

import hudson.plugins.ec2.win.winrm.soap.Namespaces;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Base64;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;

public class SendInputRequest extends AbstractWinRMRequest {

    byte[] input;
    private final String commandId, shellId;

    public SendInputRequest(URL url, byte[] input, String shellId, String commandId) {
        super(url);
        this.input = input.clone();
        this.commandId = commandId;
        this.shellId = shellId;
    }

    @Override
    protected void construct() {
        try {
            defaultHeader()
                    .action(new URI("http://schemas.microsoft.com/wbem/wsman/1/windows/shell/Send"))
                    .resourceURI(new URI("http://schemas.microsoft.com/wbem/wsman/1/windows/shell/cmd"))
                    .shellId(shellId);

            Element body = DocumentHelper.createElement(QName.get("Send", Namespaces.NS_WIN_SHELL));
            body.addElement(QName.get("Stream", Namespaces.NS_WIN_SHELL))
                    .addAttribute("Name", "stdin")
                    .addAttribute("CommandId", commandId)
                    .addText(Base64.getEncoder().encodeToString(input));
            setBody(body);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Error while building request content", e);
        }
    }
}
