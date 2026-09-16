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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.WorkspaceAction;
import org.jenkinsci.plugins.workflow.flow.ErrorCondition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.LinearBlockHoppingScanner;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.support.steps.AgentErrorCondition;
import org.kohsuke.accmod.restrictions.suppressions.SuppressRestrictedWarnings;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Qualifies a {@code node} block for retry when EC2 took the spot instance under it back.
 *
 * <p>A narrower {@link AgentErrorCondition}: that one retries a build that lost its agent for any
 * reason at all, which includes the agent dying of something the next one will die of too. A spot
 * interruption is the case where retrying is nearly always right, because nothing about the build
 * caused it and the capacity it needs is somewhere else.
 *
 * <pre>
 * retry(count: 2, conditions: [ec2SpotInterruption()]) {
 *     node('linux') {
 *         sh './build'
 *     }
 * }
 * </pre>
 *
 * <p>The {@code node} block has to be inside the {@code retry} rather than around it, so that a
 * second attempt asks for an agent again instead of returning to the one that has gone.
 */
@SuppressFBWarnings(
        value = "SE_NO_SERIALVERSIONID",
        justification = "Serialization happens exclusively through XStream and not Java Serialization.")
// Both are still marked beta upstream. The kubernetes plugin builds its own condition on the same
// two, so the shape of them is settled in practice whatever the annotation says.
@SuppressRestrictedWarnings({ErrorCondition.class, AgentErrorCondition.class})
public class EC2SpotInterruptionErrorCondition extends ErrorCondition {

    private static final Logger LOGGER = Logger.getLogger(EC2SpotInterruptionErrorCondition.class.getName());

    @DataBoundConstructor
    public EC2SpotInterruptionErrorCondition() {}

    @Override
    public boolean test(@NonNull Throwable t, @CheckForNull StepContext context)
            throws IOException, InterruptedException {
        if (context == null) {
            LOGGER.fine("Cannot tell what an error was without its context");
            return false;
        }
        /*
         * Losing the agent is the precondition, and this is the same question the general condition
         * asks. Anything it would not retry is a failure of the build rather than of the hardware
         * under it, whatever EC2 did with the instance afterwards.
         */
        if (!new AgentErrorCondition().test(t, context)) {
            LOGGER.fine(() -> "Not a failure that looks like a lost agent: " + t);
            return false;
        }
        FlowNode origin = ErrorAction.findOrigin(t, context.get(FlowExecution.class));
        if (origin == null) {
            return false;
        }
        FlowNode start = origin instanceof BlockEndNode ? ((BlockEndNode) origin).getStartNode() : origin;
        LinearBlockHoppingScanner scanner = new LinearBlockHoppingScanner();
        scanner.setup(start);
        TaskListener listener = context.get(TaskListener.class);
        for (FlowNode callStack : scanner) {
            /*
             * The workspace records the name of the agent the block ran on, and it survives the
             * agent. By the time a build is asked why it failed the node is usually deleted, so
             * there is nothing left to ask directly.
             */
            WorkspaceAction workspace = callStack.getPersistentAction(WorkspaceAction.class);
            if (workspace != null) {
                String node = workspace.getNode();
                if (EC2SpotInterruptions.wasInterrupted(node)) {
                    log(listener, node + " was lost to an EC2 spot interruption");
                    return true;
                }
                log(listener, node + " was not lost to an EC2 spot interruption");
                return false;
            }
        }
        log(
                listener,
                "Could not find the node block behind " + start.getDisplayFunctionName()
                        + "; make sure retry is outside node, not inside");
        return false;
    }

    private static void log(@CheckForNull TaskListener listener, String message) {
        LOGGER.log(Level.FINE, message);
        if (listener != null) {
            listener.getLogger().println(message);
        }
    }

    @Symbol("ec2SpotInterruption")
    @Extension(optional = true)
    @SuppressRestrictedWarnings(ErrorCondition.class)
    public static final class DescriptorImpl extends ErrorConditionDescriptor {

        @Override
        @NonNull
        public String getDisplayName() {
            return "EC2 spot interruption";
        }
    }
}
