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

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

/**
 * @author Kohsuke Kawaguchi
 */
public class EC2PrivateKeyTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();
    
    @Test
    public void testFingerprint() throws IOException {
        EC2PrivateKey k = new EC2PrivateKey("-----BEGIN RSA PRIVATE KEY-----\n"
                + "MIIEowIBAAKCAQEAlpK/pGxCRoHpbIObxYW53fl4qA+EQNHuSveNyxt+6m/HAdRLhEMGHe7/b7dR\n"
                + "e8bnJLtJD7+rTyKnhIiAQ3ZKSAXUNjbcwnH/lxfT39ht/PkupK0Vbdzgdm4vYfciFsqO/H1T5WPb\n"
                + "YRRPXcSWg5pInH7XtLOQxotXH5Kqrltvy+Fn6VmVCqJdFaRwWPAAPVbwlPmd7EHfsHNBNsYPV51D\n"
                + "bVPTDx8YGsvJ9Qua54ST5vrkdUzUrC7UK5cqPAAIQCWFzH07/xzhtxK3282GPKhxM87zFABIlFfp\n"
                + "a8nPZEEhYG62u7KQRHWDbF+IRWhvn8llBsCQaelEz3U65TdoEKQRzQIDAQABAoIBAHyksAXBJD/P\n"
                + "jNYqQAmLgGgS+mFMrvMllPfz4ymt8irJKtkFzxmGjgq7bDIjc01eQrsyWfGyfXH9wuRARsURp73l\n"
                + "LV1PnwFLcwO1UsurEqll8MmbCfEu9ZSz839KH6r0NNcoPAnY1qKPOH/rm5kHX3JEwfUw6/ifIhjd\n"
                + "xXKd+HaxLWTyZ85jdOh3haB+xP5j1VwrdrgbD661RnK1nEW/1Ofqa0gLkrbtwYPF1MiRhlbJC6jb\n"
                + "jvA5zTANpTCifh4zlt+GZQkFIXcLITr4eAvBWLHT6V82XQb9HNs0zXCSceIFc1eUoYsHcJ7Qqdbn\n"
                + "H8H9SN+nmJSTxjo8kcZBN3ptw/UCgYEA8wRytf6ch8p3ZAXN8J5NxZ5IGx5H14BWp1VgjjJpVTjC\n"
                + "mCPfwGAZT9btYPoBEq0Qdxvm+A6fvEZ/K5wZISgsJMNE+IF7XgLDTiXhhQpH5QnyCtOU/TAlN1yQ\n"
                + "F3aI3nDS35w0pimDrLaTe5byf/RTApQkKoZumR5MCh/4T6czP8MCgYEAnp4EUVs3n+fKiH2Ztqgk\n"
                + "2EVxEkqYwTkPDEYa4Z/VsCd7A1kbTml4WndTUwtK6Z5ytrB6TMXpI/AfU5Xd1svcdQY7RFG9kfki\n"
                + "y5oQP+/eUNVI7eFuR8Zu+WzaWSxVi+3SztiYvE+S2Jd8oQFMrTM7VE949DT9fII1sMmv2QkGXy8C\n"
                + "gYEAxAZSgXtfyCkJJSWJeQ44ra9/emBykuJzA4da21jOnm+qiA5n7kWWJVC5KgB/3RC8t1dKd81U\n"
                + "DArRidvgaV5+PSlF+S541NxlriPgRfCFDbt4AkOpapHrczy2/jYfMU7Qyo616VKTZD3huU+JTK1I\n"
                + "SEw24BaQH/LQY1pmcdns/QECgYAqkLcR6gukUryMIkCEvtycWQ493VzexWQfZBTEpXLfwciGHnxw\n"
                + "b2dHx6vJpkclKEsacYNwZM/qv/54HMiaYry3fsOa0uCvco7+2kowDju3r3TRuWQxyLNxJd/2fCo8\n"
                + "0cZ3kbJzHluG2igswL+F3zC1sFoCFtJLflnQJl+VO5HFKwKBgD5F/+paTIYc622xiDeCYsYfqnpq\n"
                + "MpZJorRzNPGTxmv7Kg94kFh7h7zuccUcNn15iUpNRTwLUZpKArYcuU1bhnveBD4l+84XBii6mFjz\n"
                + "9ontJin0nlHPk+AOmV8xt3yYD+wPAJy5MjUco7tS4Ix6bmvxcpZi2ZcHT1GwkiIzgKWE\n"
                + "-----END RSA PRIVATE KEY-----");
        assertEquals("3c:ee:c2:12:57:5f:d0:73:79:38:d6:aa:ef:91:0a:b8:2c:5f:47:65", k.getFingerprint());
    }

    @Test
    public void testPublicFingerprint() throws IOException {
        EC2PrivateKey k = new EC2PrivateKey("-----BEGIN RSA PRIVATE KEY-----\n"
                + "MIIEowIBAAKCAQEAlpK/pGxCRoHpbIObxYW53fl4qA+EQNHuSveNyxt+6m/HAdRLhEMGHe7/b7dR\n"
                + "e8bnJLtJD7+rTyKnhIiAQ3ZKSAXUNjbcwnH/lxfT39ht/PkupK0Vbdzgdm4vYfciFsqO/H1T5WPb\n"
                + "YRRPXcSWg5pInH7XtLOQxotXH5Kqrltvy+Fn6VmVCqJdFaRwWPAAPVbwlPmd7EHfsHNBNsYPV51D\n"
                + "bVPTDx8YGsvJ9Qua54ST5vrkdUzUrC7UK5cqPAAIQCWFzH07/xzhtxK3282GPKhxM87zFABIlFfp\n"
                + "a8nPZEEhYG62u7KQRHWDbF+IRWhvn8llBsCQaelEz3U65TdoEKQRzQIDAQABAoIBAHyksAXBJD/P\n"
                + "jNYqQAmLgGgS+mFMrvMllPfz4ymt8irJKtkFzxmGjgq7bDIjc01eQrsyWfGyfXH9wuRARsURp73l\n"
                + "LV1PnwFLcwO1UsurEqll8MmbCfEu9ZSz839KH6r0NNcoPAnY1qKPOH/rm5kHX3JEwfUw6/ifIhjd\n"
                + "xXKd+HaxLWTyZ85jdOh3haB+xP5j1VwrdrgbD661RnK1nEW/1Ofqa0gLkrbtwYPF1MiRhlbJC6jb\n"
                + "jvA5zTANpTCifh4zlt+GZQkFIXcLITr4eAvBWLHT6V82XQb9HNs0zXCSceIFc1eUoYsHcJ7Qqdbn\n"
                + "H8H9SN+nmJSTxjo8kcZBN3ptw/UCgYEA8wRytf6ch8p3ZAXN8J5NxZ5IGx5H14BWp1VgjjJpVTjC\n"
                + "mCPfwGAZT9btYPoBEq0Qdxvm+A6fvEZ/K5wZISgsJMNE+IF7XgLDTiXhhQpH5QnyCtOU/TAlN1yQ\n"
                + "F3aI3nDS35w0pimDrLaTe5byf/RTApQkKoZumR5MCh/4T6czP8MCgYEAnp4EUVs3n+fKiH2Ztqgk\n"
                + "2EVxEkqYwTkPDEYa4Z/VsCd7A1kbTml4WndTUwtK6Z5ytrB6TMXpI/AfU5Xd1svcdQY7RFG9kfki\n"
                + "y5oQP+/eUNVI7eFuR8Zu+WzaWSxVi+3SztiYvE+S2Jd8oQFMrTM7VE949DT9fII1sMmv2QkGXy8C\n"
                + "gYEAxAZSgXtfyCkJJSWJeQ44ra9/emBykuJzA4da21jOnm+qiA5n7kWWJVC5KgB/3RC8t1dKd81U\n"
                + "DArRidvgaV5+PSlF+S541NxlriPgRfCFDbt4AkOpapHrczy2/jYfMU7Qyo616VKTZD3huU+JTK1I\n"
                + "SEw24BaQH/LQY1pmcdns/QECgYAqkLcR6gukUryMIkCEvtycWQ493VzexWQfZBTEpXLfwciGHnxw\n"
                + "b2dHx6vJpkclKEsacYNwZM/qv/54HMiaYry3fsOa0uCvco7+2kowDju3r3TRuWQxyLNxJd/2fCo8\n"
                + "0cZ3kbJzHluG2igswL+F3zC1sFoCFtJLflnQJl+VO5HFKwKBgD5F/+paTIYc622xiDeCYsYfqnpq\n"
                + "MpZJorRzNPGTxmv7Kg94kFh7h7zuccUcNn15iUpNRTwLUZpKArYcuU1bhnveBD4l+84XBii6mFjz\n"
                + "9ontJin0nlHPk+AOmV8xt3yYD+wPAJy5MjUco7tS4Ix6bmvxcpZi2ZcHT1GwkiIzgKWE\n"
                + "-----END RSA PRIVATE KEY-----");
        assertEquals("e3:cc:f6:5d:0b:bb:8b:ca:32:12:fd:70:98:57:c0:21", k.getPublicFingerprint());
    }
}
