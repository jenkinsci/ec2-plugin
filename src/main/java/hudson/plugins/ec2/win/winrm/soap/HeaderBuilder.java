package hudson.plugins.ec2.win.winrm.soap;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HeaderBuilder {
    private String to;
    private String replyTo;
    private String maxEnvelopeSize;
    private String timeout;
    private String locale;
    private String id;
    private String action;
    private String shellId;
    private String resourceURI;
    private List<Option> optionSet;

    HeaderBuilder() {}

    public HeaderBuilder to(URI address) {
        to = address.toString();
        return this;
    }

    public HeaderBuilder replyTo(URI address) {
        replyTo = address.toString();
        return this;
    }

    public HeaderBuilder maxEnvelopeSize(int size) {
        maxEnvelopeSize = Integer.toString(size);
        return this;
    }

    public HeaderBuilder id(String id) {
        this.id = id;
        return this;
    }

    public HeaderBuilder locale(String locale) {
        this.locale = locale;
        return this;
    }

    public HeaderBuilder timeout(String timeout) {
        this.timeout = timeout;
        return this;
    }

    public HeaderBuilder action(URI uri) {
        this.action = uri.toString();
        return this;
    }

    public HeaderBuilder shellId(String shellId) {
        this.shellId = shellId;
        return this;
    }

    public HeaderBuilder resourceURI(URI uri) {
        this.resourceURI = uri.toString();
        return this;
    }

    public HeaderBuilder options(List<Option> options) {
        this.optionSet =
                options != null ? Collections.unmodifiableList(new ArrayList<>(options)) : Collections.emptyList();
        return this;
    }

    public Header build() {
        return new Header(to, replyTo, maxEnvelopeSize, timeout, locale, id, action, shellId, resourceURI, optionSet);
    }
}
