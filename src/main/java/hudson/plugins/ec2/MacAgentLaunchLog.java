/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Replaces Jenkins core's "This is a Unix agent" launch-log line for EC2 Mac agents.
 */
final class MacAgentLaunchLog extends FilterOutputStream {
    static final String UNIX_AGENT_LINE = "This is a Unix agent";
    static final String MAC_AGENT_LINE = "This is a Mac agent";

    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    MacAgentLaunchLog(OutputStream out) {
        super(out);
    }

    @Override
    public synchronized void write(int b) throws IOException {
        if (b == '\n') {
            flushLine();
            out.write('\n');
        } else if (b != '\r') {
            line.write(b);
        }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        for (int i = 0; i < len; i++) {
            write(b[off + i] & 0xFF);
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        if (line.size() > 0) {
            out.write(line.toByteArray());
            line.reset();
        }
        super.flush();
    }

    private void flushLine() throws IOException {
        String text = line.toString(StandardCharsets.UTF_8);
        line.reset();
        if (UNIX_AGENT_LINE.equals(text)) {
            text = MAC_AGENT_LINE;
        }
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }
}
