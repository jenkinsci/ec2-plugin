package hudson.plugins.ec2.win.winrm;

import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContextBuilder;

public class WinRMConnectionManagerFactory {
    private static final Logger log = Logger.getLogger(WinRMConnectionManagerFactory.class.getName());

    static final WinRMHttpConnectionManager DEFAULT = new WinRMHttpConnectionManager();
    static final WinRMHttpConnectionManager SSL = new WinRMHttpConnectionManager(false);
    static final WinRMHttpConnectionManager SSL_ALLOW_SELF_SIGNED = new WinRMHttpConnectionManager(true);

    static class WinRMHttpConnectionManager {
        private final PoolingHttpClientConnectionManager connectionManager;
        private SSLConnectionSocketFactory socketFactory;

        static final int DEFAULT_MAX_PER_ROUTE = 50;
        static final int MAX_TOTAL = 2500;

        WinRMHttpConnectionManager() {
            connectionManager = new PoolingHttpClientConnectionManager();
            connectionManager.setDefaultMaxPerRoute(DEFAULT_MAX_PER_ROUTE);
            connectionManager.setMaxTotal(MAX_TOTAL);
        }

        WinRMHttpConnectionManager(boolean allowSelfSignedCertificate) {
            connectionManager = new PoolingHttpClientConnectionManager(getSslSocketFactory(allowSelfSignedCertificate));
            connectionManager.setDefaultMaxPerRoute(DEFAULT_MAX_PER_ROUTE);
            connectionManager.setMaxTotal(MAX_TOTAL);
        }

        public PoolingHttpClientConnectionManager getConnectionManager() {
            return connectionManager;
        }

        public SSLConnectionSocketFactory getSocketFactory() {
            return socketFactory;
        }

        private Registry<ConnectionSocketFactory> getSslSocketFactory(boolean allowSelfSignedCertificate) {
            log.log(Level.FINE, "Setting up getSslSocketFactory");
            try {
                if (allowSelfSignedCertificate) {
                    this.socketFactory = new SSLConnectionSocketFactory(
                            new SSLContextBuilder()
                                    .loadTrustMaterial(null, new TrustSelfSignedStrategy())
                                    .build(),
                            NoopHostnameVerifier.INSTANCE);
                    log.log(Level.FINE, "Allowing self-signed certificates");
                } else {
                    this.socketFactory = SSLConnectionSocketFactory.getSystemSocketFactory();
                    log.log(Level.FINE, "Using system socket factory");
                }
            } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
                log.log(Level.WARNING, "Exception when creating socket factory, using system socket factory");
                this.socketFactory = SSLConnectionSocketFactory.getSystemSocketFactory();
            }
            return RegistryBuilder.<ConnectionSocketFactory>create()
                    .register("https", this.socketFactory)
                    .build();
        }
    }
}
