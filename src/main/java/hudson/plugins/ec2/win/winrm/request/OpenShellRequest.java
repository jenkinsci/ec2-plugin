package hudson.plugins.ec2.win.winrm.request;

import hudson.plugins.ec2.win.winrm.soap.Namespaces;
import hudson.plugins.ec2.win.winrm.soap.Option;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;

public class OpenShellRequest extends AbstractWinRMRequest {

    public OpenShellRequest(URL url) {
        super(url);
    }

    @Override
    protected void construct() {
        try {
            defaultHeader()
                    .action(new URI("http://schemas.xmlsoap.org/ws/2004/09/transfer/Create"))
                    .resourceURI(new URI("http://schemas.microsoft.com/wbem/wsman/1/windows/shell/cmd"))
                    .options(
                            Arrays.asList(new Option("WINRS_NOPROFILE", "FALSE"), new Option("WINRS_CODEPAGE", "437")));

            Element body = DocumentHelper.createElement(QName.get("Shell", Namespaces.NS_WIN_SHELL));
            body.addElement(QName.get("InputStreams", Namespaces.NS_WIN_SHELL)).addText("stdin");
            body.addElement(QName.get("OutputStreams", Namespaces.NS_WIN_SHELL)).addText("stdout stderr");
            setBody(body);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Error while building request content", e);
        }
    }
}
