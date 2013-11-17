package hudson.plugins.ec2.win.winrm.request;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.UUID;

import hudson.plugins.ec2.win.winrm.soap.HeaderBuilder;
import hudson.plugins.ec2.win.winrm.soap.MessageBuilder;

import org.dom4j.Document;
import org.dom4j.Element;

public abstract class AbstractWinRMRequest implements WinRMRequest {

    protected MessageBuilder message = new MessageBuilder();
    protected HeaderBuilder header = message.newHeader();

    protected String timeout = "PT60S";
    protected int envelopSize = 153600;
    protected String locale = "en-US";

    protected URL url;

    public AbstractWinRMRequest(URL url) {
        this.url = url;
    }

    protected abstract void construct();



    public Document build() {
        construct();
        return message.build();
    }

    protected HeaderBuilder defaultHeader() throws URISyntaxException
    {
        return header.to(url.toURI())
                .replyTo(new URI("http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous"))
                .maxEnvelopeSize(envelopSize)
                .id(generateUUID())
                .locale(locale)
                .timeout(timeout);
    }


    protected void setBody(Element body) {
        message.addHeader(header.build());
        message.addBody(body);
    }

    protected String generateUUID() {
        return "uuid:" + UUID.randomUUID().toString().toUpperCase();
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
