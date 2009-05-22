package org.jvnet.hudson.ec2.launcher;

import java.io.OutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;

/**
 * @author Kohsuke Kawaguchi
 */
class NoCloseOutputStream extends FilterOutputStream {
    NoCloseOutputStream(OutputStream base) {
        super(base);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
        // no close
    }
}
