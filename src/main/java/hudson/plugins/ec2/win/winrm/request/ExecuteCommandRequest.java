package hudson.plugins.ec2.win.winrm.request;

import hudson.plugins.ec2.win.winrm.soap.Namespaces;
import hudson.plugins.ec2.win.winrm.soap.Option;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;

public class ExecuteCommandRequest extends AbstractWinRMRequest {

    private final String shellId;
    private final String command;

    public ExecuteCommandRequest(URL url, String shellId, String command) {
        super(url);
        this.command = command;
        this.shellId = shellId;
    }

    @Override
    protected void construct() {
        try {
            defaultHeader()
                    .action(new URI("http://schemas.microsoft.com/wbem/wsman/1/windows/shell/Command"))
                    .resourceURI(new URI("http://schemas.microsoft.com/wbem/wsman/1/windows/shell/cmd"))
                    .shellId(shellId)
                    .options(Collections.singletonList(new Option("WINRS_CONSOLEMODE_STDIN", "FALSE")));

            Element body = DocumentHelper.createElement(QName.get("CommandLine", Namespaces.NS_WIN_SHELL));
            body.addElement(QName.get("Command", Namespaces.NS_WIN_SHELL)).addText("\"" + command + "\"");
            setBody(body);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Error while building request content", e);
        }
    }
}
