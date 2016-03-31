package hudson.plugins.ec2.win.winrm.soap;

import org.dom4j.Element;
import org.dom4j.QName;

import com.google.common.collect.ImmutableList;

public class Header {
    private final String to;
    private final  String replyTo;
    private final  String maxEnvelopeSize;
    private final  String timeout;
    private final  String locale;
    private final  String id;
    private final  String action;
    private final  String shellId;
    private final  String resourceURI;
    private final  ImmutableList<Option> optionSet;

    Header(String to, String replyTo, String maxEnvelopeSize, String timeout, String locale, String id, String action, String shellId, String resourceURI, ImmutableList<Option> optionSet) {
        this.to = to;
        this.replyTo = replyTo;
        this.maxEnvelopeSize = maxEnvelopeSize;
        this.timeout = timeout;
        this.locale = locale;
        this.id = id;
        this.action = action;
        this.shellId = shellId;
        this.resourceURI = resourceURI;
        this.optionSet = optionSet;
    }

    void toElement(Element header) {
        if (to != null) {
            header.addElement(QName.get("To", Namespaces.NS_ADDRESSING)).addText(to);
        }

        if (replyTo != null) {
            mustUnderstand(header.addElement(QName.get("ReplyTo", Namespaces.NS_ADDRESSING)).addElement(QName.get("Address", Namespaces.NS_ADDRESSING))).addText(replyTo);
        }

        if (maxEnvelopeSize != null) {
            mustUnderstand(header.addElement(QName.get("MaxEnvelopeSize", Namespaces.NS_WSMAN_DMTF))).addText(maxEnvelopeSize);
        }

        if (id != null) {
            header.addElement(QName.get("MessageID", Namespaces.NS_ADDRESSING)).addText(id);
        }

        if (locale != null) {
            mustNotUnderstand(header.addElement(QName.get("Locale", Namespaces.NS_WSMAN_DMTF))).addAttribute("xml:lang", locale);
            mustNotUnderstand(header.addElement(QName.get("DataLocale", Namespaces.NS_WSMAN_MSFT))).addAttribute("xml:lang", locale);
        }

        if (timeout != null) {
            header.addElement(QName.get("OperationTimeout", Namespaces.NS_WSMAN_DMTF)).addText(timeout);
        }

        if (action != null) {
            mustUnderstand(header.addElement(QName.get("Action", Namespaces.NS_ADDRESSING))).addText(action);
        }

        if (shellId != null) {
            header.addElement(QName.get("SelectorSet", Namespaces.NS_WSMAN_DMTF)).addElement(QName.get("Selector", Namespaces.NS_WSMAN_DMTF)).addAttribute("Name", "ShellId").addText(shellId);
        }

        if (resourceURI != null) {
            mustUnderstand(header.addElement(QName.get("ResourceURI", Namespaces.NS_WSMAN_DMTF))).addText(resourceURI);
        }

        if (optionSet != null) {
            final Element set = header.addElement(QName.get("OptionSet", Namespaces.NS_WSMAN_DMTF));
            for (Option p : optionSet) {
                set.addElement(QName.get("Option", Namespaces.NS_WSMAN_DMTF)).addAttribute("Name", p.getName()).addText(p.getValue());
            }
        }
    }

    private static Element mustUnderstand(Element e) {
        return e.addAttribute("mustUnderstand", "true");
    }

    private static Element mustNotUnderstand(Element e) {
        return e.addAttribute("mustUnderstand", "false");
    }

}
