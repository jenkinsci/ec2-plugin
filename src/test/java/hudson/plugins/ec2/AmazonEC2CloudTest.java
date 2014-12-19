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

import org.jvnet.hudson.test.HudsonTestCase;

import java.util.Collections;

/**
 * @author Kohsuke Kawaguchi
 */
public class AmazonEC2CloudTest extends HudsonTestCase {

	protected void setUp() throws Exception {
		super.setUp();
		AmazonEC2Cloud.testMode = true;
	}

	protected void tearDown() throws Exception {
		super.tearDown();
		AmazonEC2Cloud.testMode = false;
	}

	public void testConfigRoundtrip() throws Exception {
		AmazonEC2Cloud orig = new AmazonEC2Cloud(true, "abc", "def", "us-east-1",
				"ghi", "3", Collections.<SlaveTemplate> emptyList());
		hudson.clouds.add(orig);
		submit(createWebClient().goTo("configure").getFormByName("config"));

		assertEqualBeans(orig, hudson.clouds.iterator().next(),
				"region,useInstanceProfileForCredentials,accessId,secretKey,privateKey,instanceCap");
	}
}
