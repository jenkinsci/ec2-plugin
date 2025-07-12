package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.plugins.ec2.win.winrm.request.DeleteShellRequest;
import hudson.plugins.ec2.win.winrm.request.ExecuteCommandRequest;
import hudson.plugins.ec2.win.winrm.request.GetOutputRequest;
import hudson.plugins.ec2.win.winrm.request.OpenShellRequest;
import hudson.plugins.ec2.win.winrm.request.SendInputRequest;
import hudson.plugins.ec2.win.winrm.request.SignalRequest;
import hudson.plugins.ec2.win.winrm.soap.Namespaces;
import java.net.URL;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.XPath;
import org.jaxen.SimpleNamespaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WinRMMessageTest {

    private URL url;
    private SimpleNamespaceContext namespaceContext;

    @BeforeEach
    void setUp() throws Exception {
        url = new URL("http://localhost");
        namespaceContext = new SimpleNamespaceContext();
        namespaceContext.addNamespace(Namespaces.NS_WIN_SHELL.getPrefix(), Namespaces.NS_WIN_SHELL.getURI());
        namespaceContext.addNamespace(Namespaces.NS_ADDRESSING.getPrefix(), Namespaces.NS_ADDRESSING.getURI());
        namespaceContext.addNamespace(Namespaces.NS_WSMAN_DMTF.getPrefix(), Namespaces.NS_WSMAN_DMTF.getURI());
        namespaceContext.addNamespace(Namespaces.NS_WSMAN_MSFT.getPrefix(), Namespaces.NS_WSMAN_MSFT.getURI());
        namespaceContext.addNamespace(Namespaces.NS_SOAP_ENV.getPrefix(), Namespaces.NS_SOAP_ENV.getURI());
    }

    @Test
    void testOpenShellMessage() {
        OpenShellRequest r = new OpenShellRequest(url);
        assertEquals("http://schemas.xmlsoap.org/ws/2004/09/transfer/Create", xpath("//a:Action", r.build()));
        assertEquals("http://localhost", xpath("//a:To", r.build()));
        assertEquals("stdin", xpath("//env:Body/rsp:Shell/rsp:InputStreams", r.build()));
        assertEquals("stdout stderr", xpath("//env:Body/rsp:Shell/rsp:OutputStreams", r.build()));
    }

    @Test
    void testDeleteShellMessage() {
        DeleteShellRequest r = new DeleteShellRequest(url, "SHELLID");
        assertEquals("http://schemas.xmlsoap.org/ws/2004/09/transfer/Delete", xpath("//a:Action", r.build()));
        assertEquals("http://localhost", xpath("//a:To", r.build()));
        assertEquals("SHELLID", xpath("//w:Selector[@Name=\"ShellId\"]", r.build()));
    }

    @Test
    void testExecuteCommandMessage() {
        ExecuteCommandRequest r = new ExecuteCommandRequest(url, "SHELLID", "ipconfig /all");
        assertEquals("http://schemas.microsoft.com/wbem/wsman/1/windows/shell/Command", xpath("//a:Action", r.build()));
        assertEquals("http://localhost", xpath("//a:To", r.build()));
        assertEquals("SHELLID", xpath("//w:Selector[@Name=\"ShellId\"]", r.build()));
        assertEquals("FALSE", xpath("//w:Option[@Name=\"WINRS_CONSOLEMODE_STDIN\"]", r.build()));
        assertEquals("\"ipconfig /all\"", xpath("//rsp:CommandLine/rsp:Command", r.build()));
    }

    @Test
    void testGetOutputMessage() {
        GetOutputRequest r = new GetOutputRequest(url, "SHELLID", "COMMANDID");
        assertEquals("http://schemas.microsoft.com/wbem/wsman/1/windows/shell/Receive", xpath("//a:Action", r.build()));
        assertEquals("http://localhost", xpath("//a:To", r.build()));
        assertEquals("SHELLID", xpath("//w:Selector[@Name=\"ShellId\"]", r.build()));
        assertEquals("stdout stderr", xpath("//rsp:Receive/rsp:DesiredStream[@CommandId=\"COMMANDID\"]", r.build()));
    }

    @Test
    void testSendInputMessage() {
        SendInputRequest r = new SendInputRequest(url, new byte[] {31, 32}, "SHELLID", "COMMANDID");
        assertEquals("http://schemas.microsoft.com/wbem/wsman/1/windows/shell/Send", xpath("//a:Action", r.build()));
        assertEquals("http://localhost", xpath("//a:To", r.build()));
        assertEquals("SHELLID", xpath("//w:Selector[@Name=\"ShellId\"]", r.build()));
        assertEquals("HyA=", xpath("//rsp:Send/rsp:Stream[@CommandId=\"COMMANDID\"]", r.build()));
    }

    @Test
    void testSignalMessage() {
        SignalRequest r = new SignalRequest(url, "SHELLID", "COMMANDID");
        assertEquals("http://schemas.microsoft.com/wbem/wsman/1/windows/shell/Command", xpath("//a:Action", r.build()));
        assertEquals("http://localhost", xpath("//a:To", r.build()));
        assertEquals("SHELLID", xpath("//w:Selector[@Name=\"ShellId\"]", r.build()));
        assertEquals(
                "http://schemas.microsoft.com/wbem/wsman/1/windows/shell/signal/terminate",
                xpath("//rsp:Signal[@CommandId=\"COMMANDID\"]/rsp:Code", r.build()));
    }

    private String xpath(String xpath, Document doc) {
        XPath xp = DocumentHelper.createXPath(xpath);
        xp.setNamespaceContext(namespaceContext);
        return xp.valueOf(doc);
    }
}
