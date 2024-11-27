package hudson.plugins.ec2.win.winrm.soap;

import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.Namespace;
import org.dom4j.QName;

public class MessageBuilder {
    private final Document doc = DocumentHelper.createDocument();
    private final Element envelope = doc.addElement(QName.get("Envelope", Namespaces.NS_SOAP_ENV));

    public MessageBuilder() {
        for (Namespace ns : Namespaces.mostUsed()) {
            envelope.add(ns);
        }
    }

    public HeaderBuilder newHeader() {
        return new HeaderBuilder();
    }

    public void addHeader(Header header) {
        Element elem = envelope.addElement(QName.get("Header", Namespaces.NS_SOAP_ENV));
        header.toElement(elem);
    }

    public void addBody(Element body) {
        Element elem = envelope.addElement(QName.get("Body", Namespaces.NS_SOAP_ENV));
        if (body != null) {
            elem.add(body);
        }
    }

    public Document build() {
        return doc;
    }
}
