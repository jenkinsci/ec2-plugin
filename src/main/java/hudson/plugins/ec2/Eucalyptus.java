package hudson.plugins.ec2;

import hudson.Extension;
import hudson.util.FormValidation;
import hudson.util.IOException2;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletException;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerResponse;

/**
 * Eucalyptus.
 *
 * @author Kohsuke Kawaguchi
 */
public class Eucalyptus extends EC2Cloud {
    private transient Metadata metadata;

    public final URL url;

    @DataBoundConstructor
    public Eucalyptus(URL url, String accessId, String secretKey, String privateKey, String instanceCapStr, List<SlaveTemplate> templates) throws IOException {
        super("eucalyptus", accessId, secretKey, privateKey, instanceCapStr, templates);
        this.url = url;
    }

    private Metadata getMetadata() throws IOException {
        if (metadata==null)
            metadata = new Metadata(url);
        return metadata;
    }

    @Override
    public URL getEc2EndpointUrl() throws IOException {
        return getMetadata().ec2endpoint;
    }

    @Override
    public URL getS3EndpointUrl() throws IOException {
        return getMetadata().s3endpoint;
    }

    @Extension
    public static class DescriptorImpl extends EC2Cloud.DescriptorImpl {
        @Override
		public String getDisplayName() {
            return "Eucalyptus";
        }

        @Override
		public FormValidation doTestConnection(
                @QueryParameter URL url,
                @QueryParameter String accessId,
                @QueryParameter String secretKey,
                @QueryParameter String privateKey) throws IOException, ServletException {
            return super.doTestConnection(new Metadata(url).ec2endpoint,accessId,secretKey,privateKey);
        }

        @Override
		public FormValidation doGenerateKey(
                StaplerResponse rsp, @QueryParameter URL url, @QueryParameter String accessId, @QueryParameter String secretKey) throws IOException, ServletException {
            return super.doGenerateKey(rsp, new Metadata(url).ec2endpoint, accessId,secretKey);
        }
    }

    /**
     * Eucalyptus service endpoint metadata.
     */
    static class Metadata {
        final URL ec2endpoint,s3endpoint;

        Metadata(URL eucalyptus) throws IOException {
            if (!eucalyptus.getProtocol().equals("https"))
                throw new IOException("Expecting an HTTPS URL but got "+eucalyptus);
            URL metadataUrl = new URL(eucalyptus, "/register");
            try {
                HttpsURLConnection con = (HttpsURLConnection)metadataUrl.openConnection();
                makeIgnoreCertificate(con);
                Document metadata = new SAXReader().read(con.getInputStream());
                /*
                       Metadata, as of Eucalyptus 1.5.2, looks like this:

                       <Signature>
                         <SignedInfo>
                           <SignatureMethod>http://www.w3.org/2001/04/xmldsig-more#hmac-sha256</SignatureMethod>
                         </SignedInfo>
                         <SignatureValue>62595777525d7dbba4b5f361b3e9041d3d37e92611684557e67e85a9222a3ffb  </SignatureValue>
                         <Object>
                          <CloudSchema>
                         <Services type="array">
                           <Service>
                             <Name>ec2</Name>
                             <EndpointUrl>http://eucalyptus.hudson-slaves.sfbay.sun.com:8773/services/Eucalyptus</EndpointUrl>
                             <Resources type="array">
                               ...
                             </Resources>
                           </Service>
                           <Service>
                             <Name>s3</Name>
                             <EndpointUrl>http://eucalyptus.hudson-slaves.sfbay.sun.com:8773/services/Walrus</EndpointUrl>
                             <Resources type="array">
                               ...
                             </Resources>
                           </Service>
                         </Services>
                         <id>a002c56e-b994-4ed8-956b-b30eda9b6153</id>  <CloudType>eucalyptus</CloudType>
                         <CloudVersion>1.5.2</CloudVersion>
                         <SchemaVersion>1.0</SchemaVersion>
                         <Description>Public cloud in the new cluster</Description>
                       </CloudSchema>

                    */

                this.ec2endpoint = readURLFromMetadata(metadata, "ec2");
                this.s3endpoint = readURLFromMetadata(metadata, "s3");
            } catch (DocumentException e) {
                throw new IOException2("Failed to parse Eucalyptus metadata at "+metadataUrl,e);
            } catch (IOException e) {
                throw new IOException2("Failed to parse Eucalyptus metadata at "+metadataUrl,e);
            } catch (GeneralSecurityException e) {
                throw new IOException2("Failed to parse Eucalyptus metadata at "+metadataUrl,e);
            }
        }

        /**
         * Configures the given {@link HttpsURLConnection} so that it'll ignore all the HTTPS certificate checks,
         * as typical Eucalyptus implementation doesn't come with a valid certificate.
         */
        private void makeIgnoreCertificate(HttpsURLConnection con) throws NoSuchAlgorithmException, KeyManagementException {
            SSLContext sc = SSLContext.getInstance("SSL");
            TrustManager[] tma = {new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }

                public void checkClientTrusted(X509Certificate[] certs, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] certs, String authType) {
                }
            }};
            sc.init(null, tma, null);

            con.setSSLSocketFactory(sc.getSocketFactory());
            con.setHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String s, SSLSession sslSession) {
                    return true;    // everything goes
                }
            });
        }

        private URL readURLFromMetadata(Document metadata, String serviceName) throws MalformedURLException {
            Element e = (Element)metadata.selectSingleNode("//Service[Name/text()='" + serviceName + "']/EndpointUrl");
            if (e==null)
                throw new IllegalStateException("Service metadata didn't contain "+serviceName);
            return new URL(e.getTextTrim());
        }
    }
}
