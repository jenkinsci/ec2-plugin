package hudson.plugins.ec2.win.winrm.soap;

import com.google.common.collect.ImmutableList;
import java.util.List;
import org.dom4j.Namespace;

public class Namespaces {

    public static final Namespace NS_SOAP_ENV = Namespace.get("env", "http://www.w3.org/2003/05/soap-envelope");
    public static final Namespace NS_ADDRESSING = Namespace.get("a", "http://schemas.xmlsoap.org/ws/2004/08/addressing");
    public static final Namespace NS_WSMAN_DMTF = Namespace.get("w", "http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd");
    public static final Namespace NS_WSMAN_MSFT = Namespace.get("p", "http://schemas.microsoft.com/wbem/wsman/1/wsman.xsd");
    public static final Namespace NS_WIN_SHELL = Namespace.get("rsp", "http://schemas.microsoft.com/wbem/wsman/1/windows/shell");

    private Namespaces() {
    }

    public static List<Namespace> mostUsed() {
        return ImmutableList.of(NS_SOAP_ENV, NS_ADDRESSING, NS_WIN_SHELL, NS_WSMAN_DMTF, NS_WSMAN_MSFT);
    }
}
