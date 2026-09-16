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
import hudson.ExtensionPoint;
import hudson.model.Run;

/**
 * Writes a line into the log of a build that is still running.
 *
 * <p>Jenkins core offers no way to do this for a build in general, only per build type, and the one
 * type this plugin can reach is supplied by an optional dependency. An extension point keeps that
 * dependency out of the callers: where Pipeline is absent no implementation is registered and the
 * line simply goes unwritten.
 */
public interface EC2BuildLogReporter extends ExtensionPoint {

    /**
     * Writes the message to the build's log, doing nothing if this reporter does not know how to
     * reach the log of that particular kind of build.
     */
    void report(@NonNull Run<?, ?> run, @NonNull String message);
}
