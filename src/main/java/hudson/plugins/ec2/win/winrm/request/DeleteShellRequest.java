package hudson.plugins.ec2.win.winrm.request;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

public class DeleteShellRequest extends AbstractWinRMRequest {

    private final String shellId;

    public DeleteShellRequest(URL url, String shellId) {
        super(url);
        this.shellId = shellId;
    }

    @Override
    protected void construct() {
        try {
            defaultHeader().action(new URI("http://schemas.xmlsoap.org/ws/2004/09/transfer/Delete"))
                    .shellId(shellId)
                    .resourceURI(new URI("http://schemas.microsoft.com/wbem/wsman/1/windows/shell/cmd"));

            setBody(null);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Error while building request content", e);
        }
    }
}
