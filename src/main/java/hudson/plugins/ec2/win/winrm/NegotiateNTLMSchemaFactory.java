package hudson.plugins.ec2.win.winrm;

import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.auth.*;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.impl.auth.NTLMScheme;
import org.apache.http.message.BufferedHeader;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.CharArrayBuffer;

public class NegotiateNTLMSchemaFactory implements AuthSchemeFactory, AuthSchemeProvider {

    public AuthScheme newInstance(HttpParams params) {
        return new NegotiateNTLM();
    }

    public AuthScheme create(HttpContext context) {
        return new NegotiateNTLM();
    }

    public static class NegotiateNTLM extends NTLMScheme {
        @Override
        public String getSchemeName() {
            return AuthSchemes.SPNEGO;
        }

        @Override
        public Header authenticate(Credentials credentials, HttpRequest request) throws AuthenticationException {
            Credentials ntCredentials = credentials;
            if (!(credentials instanceof NTCredentials)) {
                ntCredentials = new NTCredentials(credentials.getUserPrincipal().getName(), credentials.getPassword(), null, null);
            }
            Header header = super.authenticate(ntCredentials, request);
            //need replace NTLM with Negotiate
            CharArrayBuffer buffer = new CharArrayBuffer(512);
            buffer.append(header.getName());
            buffer.append(": ");
            buffer.append(header.getValue().replaceFirst("NTLM", "Negotiate"));
            return new BufferedHeader(buffer);
        }
    }
}
