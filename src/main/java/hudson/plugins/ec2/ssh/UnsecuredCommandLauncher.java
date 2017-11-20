/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Stephen Connolly
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
package hudson.plugins.ec2.ssh;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Util;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Slave;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.util.StreamCopyThread;
import hudson.util.FormValidation;
import hudson.util.ProcessTree;

import java.io.IOException;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.scriptsecurity.scripts.UnapprovedUsageException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.QueryParameter;

/**
 * Copy-paste of the {@link hudson.slaves.CommandLauncher} class without the security check on the script approval
 * to solve the JENKINS-47593
 */
@Restricted(NoExternalUse.class)
public class UnsecuredCommandLauncher extends ComputerLauncher {

    /**
     * Command line to launch the agent, like
     * "ssh myslave java -jar /path/to/hudson-remoting.jar"
     */
    private final String agentCommand;

    /**
     * Optional environment variables to add to the current environment. Can be null.
     */
    private final EnvVars env;

    /** Constructor used only in {@link EC2UnixLauncher} to avoid script approval issue */
    public UnsecuredCommandLauncher(String command) {
        agentCommand = command;
        env = null;
    }

    public String getCommand() {
        return agentCommand;
    }

    /**
     * Gets the formatted current time stamp.
     */
    private static String getTimestamp() {
        return String.format("[%1$tD %1$tT]", new Date());
    }

    @Override
    public void launch(SlaveComputer computer, final TaskListener listener) {
        EnvVars _cookie = null;
        Process _proc = null;
        try {
            Slave node = computer.getNode();
            if (node == null) {
                throw new AbortException("Cannot launch commands on deleted nodes");
            }

            listener.getLogger().println(hudson.plugins.ec2.ssh.Messages.Slave_Launching(getTimestamp()));
            String command = getCommand();
            if (command.trim().length() == 0) {
                listener.getLogger().println(hudson.plugins.ec2.ssh.Messages.CommandLauncher_NoLaunchCommand());
                return;
            }
            listener.getLogger().println("$ " + command);

            ProcessBuilder pb = new ProcessBuilder(Util.tokenize(command));
            final EnvVars cookie = _cookie = EnvVars.createCookie();
            pb.environment().putAll(cookie);
            pb.environment().put("WORKSPACE", StringUtils.defaultString(computer.getAbsoluteRemoteFs(), node.getRemoteFS())); //path for local slave log

            {// system defined variables
                String rootUrl = Jenkins.getInstance().getRootUrl();
                if (rootUrl!=null) {
                    pb.environment().put("HUDSON_URL", rootUrl);    // for backward compatibility
                    pb.environment().put("JENKINS_URL", rootUrl);
                    pb.environment().put("SLAVEJAR_URL", rootUrl+"/jnlpJars/agent.jar");
                    pb.environment().put("AGENTJAR_URL", rootUrl+"/jnlpJars/agent.jar");
                }
            }

            if (env != null) {
                pb.environment().putAll(env);
            }

            final Process proc = _proc = pb.start();

            // capture error information from stderr. this will terminate itself
            // when the process is killed.
            new StreamCopyThread("stderr copier for remote agent on " + computer.getDisplayName(),
                    proc.getErrorStream(), listener.getLogger()).start();

            computer.setChannel(proc.getInputStream(), proc.getOutputStream(), listener.getLogger(), new Channel.Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    reportProcessTerminated(proc, listener);

                    try {
                        ProcessTree.get().killAll(proc, cookie);
                    } catch (InterruptedException e) {
                        LOGGER.log(Level.INFO, "interrupted", e);
                    }
                }
            });

            LOGGER.info("agent launched for " + computer.getDisplayName());
        } catch (InterruptedException e) {
            e.printStackTrace(listener.error(hudson.slaves.Messages.ComputerLauncher_abortedLaunch()));
        } catch (UnapprovedUsageException e) {
            listener.error(e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace(listener.error(hudson.slaves.Messages.ComputerLauncher_unexpectedError()));
        } catch (Error e) {
            e.printStackTrace(listener.error(hudson.slaves.Messages.ComputerLauncher_unexpectedError()));
        } catch (IOException e) {
            Util.displayIOException(e, listener);

            String msg = Util.getWin32ErrorMessage(e);
            if (msg == null) {
                msg = "";
            } else {
                msg = " : " + msg;
                // FIXME TODO i18n what is this!?
            }
            msg = hudson.plugins.ec2.ssh.Messages.Slave_UnableToLaunch(computer.getDisplayName(), msg);
            LOGGER.log(Level.SEVERE, msg, e);
            e.printStackTrace(listener.error(msg));

            if(_proc!=null) {
                reportProcessTerminated(_proc, listener);
                try {
                    ProcessTree.get().killAll(_proc, _cookie);
                } catch (InterruptedException x) {
                    x.printStackTrace(listener.error(hudson.slaves.Messages.ComputerLauncher_abortedLaunch()));
                }
            }
        }
    }

    private static void reportProcessTerminated(Process proc, TaskListener listener) {
        try {
            int exitCode = proc.exitValue();
            listener.error("Process terminated with exit code " + exitCode);
        } catch (IllegalThreadStateException e) {
            // hasn't terminated yet
        }
    }

    private static final Logger LOGGER = Logger.getLogger(CommandLauncher.class.getName());

    @Extension @Symbol("unsecuredCommand")
    public static class DescriptorImpl extends Descriptor<ComputerLauncher> {
        public String getDisplayName() {
            return hudson.plugins.ec2.ssh.Messages.CommandLauncher_displayName();
        }

        public FormValidation doCheckCommand(@QueryParameter String value) {
            if(Util.fixEmptyAndTrim(value)==null)
                return FormValidation.error(hudson.plugins.ec2.ssh.Messages.CommandLauncher_NoLaunchCommand());
            else
                return ScriptApproval.get().checking(value, SystemCommandLanguage.get());
        }
    }
}
