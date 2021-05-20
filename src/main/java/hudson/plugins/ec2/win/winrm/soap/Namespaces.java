package hudson.plugins.ec2.win.winrm.soap;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.dom4j.Namespace;

public class Namespaces {
    public static final Namespace NS_SOAP_ENV = Namespace.get("env", "http://www.w3.org/2003/05/soap-envelope");
    public static final Namespace NS_ADDRESSING = Namespace.get("a", "http://schemas.xmlsoap.org/ws/2004/08/addressing");
    public static final Namespace NS_CIMBINDING = Namespace.get("b", "http://schemas.dmtf.org/wbem/wsman/1/cimbinding.xsd");
    public static final Namespace NS_ENUM = Namespace.get("n", "http://schemas.xmlsoap.org/ws/2004/09/enumeration");
    public static final Namespace NS_TRANSFER = Namespace.get("x", "http://schemas.xmlsoap.org/ws/2004/09/transfer");
    public static final Namespace NS_WSMAN_DMTF = Namespace.get("w", "http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd");
    public static final Namespace NS_WSMAN_MSFT = Namespace.get("p", "http://schemas.microsoft.com/wbem/wsman/1/wsman.xsd");
    public static final Namespace NS_SCHEMA_INST = Namespace.get("xsi", "http://www.w3.org/2001/XMLSchema-instance");
    public static final Namespace NS_WIN_SHELL = Namespace.get("rsp", "http://schemas.microsoft.com/wbem/wsman/1/windows/shell");
    public static final Namespace NS_WSMAN_FAULT = Namespace.get("f", "http://schemas.microsoft.com/wbem/wsman/1/wsmanfault");

    private Namespaces() {
    }

    public static final List<Namespace> mostUsed() {
        return Collections.unmodifiableList(Arrays.asList(NS_SOAP_ENV, NS_ADDRESSING, NS_WIN_SHELL, NS_WSMAN_DMTF, NS_WSMAN_MSFT));
    }
}
