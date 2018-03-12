package hudson.plugins.ec2.win.winrm;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import hudson.plugins.ec2.win.winrm.request.RequestFactory;
import hudson.plugins.ec2.win.winrm.soap.Namespaces;
import hudson.remoting.FastPipedOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.params.AuthPolicy;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.XPath;
import org.jaxen.SimpleNamespaceContext;

public class WinRMClient {
    private static final Logger log = Logger.getLogger(WinRMClient.class.getName());
    private static final String APPLICATION_SOAP_XML = "application/soap+xml";

    private final URL url;
    private final String username;
    private final String password;
    private String shellId;

    private String commandId;
    private int exitCode;

    private SimpleNamespaceContext namespaceContext;

    private final RequestFactory factory;

    private final ThreadLocal<BasicAuthCache> authCache = new ThreadLocal<BasicAuthCache>();
    private boolean useHTTPS;
    private Scheme httpsScheme;
    private BasicCredentialsProvider credsProvider;

    public WinRMClient(URL url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.factory = new RequestFactory(url);
        setupHTTPClient();
    }

    public void openShell() {
        log.log(Level.FINE, "opening winrm shell to: " + url);
        Document request = factory.newOpenShellRequest().build();
        shellId = first(sendRequest(request), "//*[@Name='ShellId']");
        log.log(Level.FINER, "shellid: " + shellId);
    }

    public void executeCommand(String command) {
        log.log(Level.FINE, "winrm execute on " + shellId + " command: " + command);
        Document request = factory.newExecuteCommandRequest(shellId, command).build();
        commandId = first(sendRequest(request), "//" + Namespaces.NS_WIN_SHELL.getPrefix() + ":CommandId");
        log.log(Level.FINER, "winrm started execution on " + shellId + " commandId: " + commandId);
    }

    public void deleteShell() {
        if (shellId == null) {
            throw new IllegalStateException("no shell has been created");
        }

        log.log(Level.FINE, "closing winrm shell " + shellId);

        Document request = factory.newDeleteShellRequest(shellId).build();
        sendRequest(request);

    }

    public void signal() {
        if (commandId == null) {
            throw new IllegalStateException("no command is running");
        }

        log.log(Level.FINE, "signalling winrm shell " + shellId + " command: " + commandId);

        Document request = factory.newSignalRequest(shellId, commandId).build();
        sendRequest(request);
    }

    public void sendInput(byte[] input) {
        log.log(Level.FINE, "--> sending " + input.length);

        Document request = factory.newSendInputRequest(input, shellId, commandId).build();
        sendRequest(request);
    }

    public boolean slurpOutput(FastPipedOutputStream stdout, FastPipedOutputStream stderr) throws IOException {
        log.log(Level.FINE, "--> SlurpOutput");
        ImmutableMap<String, FastPipedOutputStream> streams = ImmutableMap.of("stdout", stdout, "stderr", stderr);

        Document request = factory.newGetOutputRequest(shellId, commandId).build();
        Document response = sendRequest(request);

        XPath xpath = DocumentHelper.createXPath("//" + Namespaces.NS_WIN_SHELL.getPrefix() + ":Stream");
        namespaceContext = new SimpleNamespaceContext();
        namespaceContext.addNamespace(Namespaces.NS_WIN_SHELL.getPrefix(), Namespaces.NS_WIN_SHELL.getURI());
        xpath.setNamespaceContext(namespaceContext);

        Base64 base64 = new Base64();
        for (Element e : (List<Element>) xpath.selectNodes(response)) {
            FastPipedOutputStream stream = streams.get(e.attribute("Name").getText().toLowerCase());
            final byte[] decode = base64.decode(e.getText());
            log.log(Level.FINE, "piping " + decode.length + " bytes from "
                    + e.attribute("Name").getText().toLowerCase());

            stream.write(decode);
        }

        XPath done = DocumentHelper.createXPath("//*[@State='http://schemas.microsoft.com/wbem/wsman/1/windows/shell/CommandState/Done']");
        done.setNamespaceContext(namespaceContext);
        if (Iterables.isEmpty(done.selectNodes(response))) {
            log.log(Level.FINE, "keep going baby!");
            return true;
        } else {
            exitCode = Integer.parseInt(first(response, "//" + Namespaces.NS_WIN_SHELL.getPrefix() + ":ExitCode"));
            log.log(Level.FINE, "no more output - command is now done - exit code: " + exitCode);
        }
        return false;
    }

    public int exitCode() {
        return exitCode;
    }

    private static String first(Document doc, String selector) {
        XPath xpath = DocumentHelper.createXPath(selector);
        try {
            return Iterables.get((List<Element>) xpath.selectNodes(doc), 0).getText();
        } catch (IndexOutOfBoundsException e) {
            throw new RuntimeException("Malformed response for " + selector + " in " + doc.asXML());
        }
    }

    private void setupHTTPClient() {
        credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT), new UsernamePasswordCredentials(username, password));
    }

    private DefaultHttpClient buildHTTPClient() {
        DefaultHttpClient httpclient = new DefaultHttpClient();
        if(! (username.contains("\\")|| username.contains("/"))){
            //user is not a domain user
            httpclient.getAuthSchemes().register(AuthPolicy.SPNEGO,new NegotiateNTLMSchemaFactory());
        }
        httpclient.setCredentialsProvider(credsProvider);
        httpclient.getParams().setParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, 5000);
        // httpclient.setHttpRequestRetryHandler(new WinRMRetryHandler());
        return httpclient;
    }

    private Document sendRequest(Document request) {
        return sendRequest(request, 0);
    }

    private Document sendRequest(Document request, int retry) {
        if (retry > 3) {
            throw new RuntimeException("Too many retry for request");
        }

        DefaultHttpClient httpclient = buildHTTPClient();
        HttpContext context = new BasicHttpContext();

        if (authCache.get() == null) {
            authCache.set(new BasicAuthCache());
        }

        context.setAttribute(ClientContext.AUTH_CACHE, authCache.get());

        if (useHTTPS) {
            httpclient.getConnectionManager().getSchemeRegistry().register(httpsScheme);
        }

        try {
            HttpPost post = new HttpPost(url.toURI());

            HttpEntity entity = new StringEntity(request.asXML(), APPLICATION_SOAP_XML, "UTF-8");
            post.setEntity(entity);

            log.log(Level.FINEST, "Request:\nPOST " + url + "\n" + request.asXML());

            HttpResponse response = httpclient.execute(post, context);
            HttpEntity responseEntity = response.getEntity();

            if (response.getStatusLine().getStatusCode() != 200) {
                // check for possible timeout

                if (response.getStatusLine().getStatusCode() == 500
                        && (responseEntity.getContentType() != null && entity.getContentType().getValue().startsWith(APPLICATION_SOAP_XML))) {
                    String respStr = EntityUtils.toString(responseEntity);
                    if (respStr.contains("TimedOut")) {
                        return DocumentHelper.parseText(respStr);
                    }
                } else {
                    // this shouldn't happen, as httpclient knows how to auth
                    // the request
                    // but I've seen it. I blame keep-alive, so we're just going
                    // to scrap the connections, and try again
                    if (response.getStatusLine().getStatusCode() == 401) {
                        // we need to force using new connections here
                        // throw away our auth cache
                        log.log(Level.WARNING, "winrm returned 401 - shouldn't happen though - retrying in 2 minutes");
                        try {
                            Thread.sleep(TimeUnit.MINUTES.toMillis(3));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        authCache.set(new BasicAuthCache());
                        log.log(Level.WARNING, "winrm returned 401 - retrying now");
                        return sendRequest(request, ++retry);
                    }
                    log.log(Level.WARNING, "winrm service " + shellId + " unexpected HTTP Response ("
                            + response.getStatusLine().getReasonPhrase() + "): "
                            + EntityUtils.toString(response.getEntity()));

                    throw new RuntimeException("Unexpected HTTP response " + response.getStatusLine().getStatusCode()
                            + " on " + url + ": " + response.getStatusLine().getReasonPhrase());
                }
            }

            if (responseEntity.getContentType() == null
                    || !entity.getContentType().getValue().startsWith(APPLICATION_SOAP_XML)) {
                throw new RuntimeException("Unexepected WinRM content type: " + entity.getContentType());
            }

            Document responseDocument = DocumentHelper.parseText(EntityUtils.toString(responseEntity));

            log.log(Level.FINEST, "Response:\n" + responseDocument.asXML());
            return responseDocument;
        } catch (URISyntaxException e) {
            throw new RuntimeException("Invalid WinRM URI " + url);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Invalid WinRM body " + request.asXML());
        } catch (ClientProtocolException e) {
            throw new RuntimeException("HTTP Error " + e.getMessage(), e);
        } catch (HttpHostConnectException e) {
            log.log(Level.SEVERE, "Can't connect to host", e);
            throw new WinRMConnectException("Can't connect to host: " + e.getMessage(), e);
        } catch (IOException e) {
            log.log(Level.SEVERE, "I/O Exception in HTTP POST", e);
            throw new RuntimeIOException("I/O Exception " + e.getMessage(), e);
        } catch (ParseException e) {
            log.log(Level.SEVERE, "XML Parse exception in HTTP POST", e);
            throw new RuntimeException("Unparseable XML in winRM response " + e.getMessage(), e);
        } catch (DocumentException e) {
            log.log(Level.SEVERE, "XML Document exception in HTTP POST", e);
            throw new RuntimeException("Invalid XML document in winRM response " + e.getMessage(), e);
        }
    }

    public String getTimeout() {
        return factory.getTimeout();
    }

    public void setTimeout(String timeout) {
        factory.setTimeout(timeout);
    }

    public void setUseHTTPS(boolean useHTTPS) {
        this.useHTTPS = useHTTPS;
        if (useHTTPS) {
            SSLSocketFactory socketFactory;
            try {
                socketFactory = new SSLSocketFactory(new TrustSelfSignedStrategy(), new AllowAllHostnameVerifier());
                httpsScheme = new Scheme("https", 443, socketFactory);
            } catch (KeyManagementException e) {
            } catch (UnrecoverableKeyException e) {
            } catch (NoSuchAlgorithmException e) {
            } catch (KeyStoreException e) {
            }
        }else{
            httpsScheme=null;
        }
    }
}
