/*
 * The MIT License
 *
 * Copyright (c) 2026, CloudBees, Inc., and a number of other of contributors
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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;

/**
 * Reaches the log of a running Pipeline build.
 */
@Extension(optional = true)
public class EC2PipelineBuildLogReporter implements EC2BuildLogReporter {

    private static final Logger LOGGER = Logger.getLogger(EC2PipelineBuildLogReporter.class.getName());

    @Override
    public void report(@NonNull Run<?, ?> run, @NonNull String message) {
        if (!(run instanceof FlowExecutionOwner.Executable executable)) {
            return;
        }
        FlowExecutionOwner owner = executable.asFlowExecutionOwner();
        if (owner == null) {
            return;
        }
        try {
            owner.getListener().getLogger().println(message);
        } catch (IOException | RuntimeException e) {
            // The build is being told something about its agent as a courtesy; it is not worth failing over.
            LOGGER.log(Level.FINE, "Could not write to the log of " + run, e);
        }
    }
}
