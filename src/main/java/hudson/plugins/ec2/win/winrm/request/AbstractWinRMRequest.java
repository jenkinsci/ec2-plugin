package hudson.plugins.ec2.win.winrm.request;

import hudson.plugins.ec2.win.winrm.soap.HeaderBuilder;
import hudson.plugins.ec2.win.winrm.soap.MessageBuilder;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.UUID;
import org.dom4j.Document;
import org.dom4j.Element;

public abstract class AbstractWinRMRequest implements WinRMRequest {

    private final MessageBuilder message = new MessageBuilder();
    private final HeaderBuilder header = message.newHeader();
    private final URL url;

    private String timeout = "PT60S";
    private int envelopSize = 153600;
    private String locale = "en-US";

    AbstractWinRMRequest(URL url) {
        this.url = url;
    }

    protected abstract void construct();

    public Document build() {
        construct();
        return message.build();
    }

    HeaderBuilder defaultHeader() throws URISyntaxException {
        return header.to(url.toURI())
                .replyTo(new URI("http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous"))
                .maxEnvelopeSize(envelopSize)
                .id(generateUUID())
                .locale(locale)
                .timeout(timeout);
    }

    void setBody(Element body) {
        message.addHeader(header.build());
        message.addBody(body);
    }

    private String generateUUID() {
        return "uuid:" + UUID.randomUUID().toString().toUpperCase();
    }

    public void setTimeout(String timeout) {
        this.timeout = timeout;
    }

    public void setEnvelopSize(int envelopSize) {
        this.envelopSize = envelopSize;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }
}
