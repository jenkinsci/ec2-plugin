package hudson.plugins.ec2.ssh;

import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2Cloud;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EC2Logger {
    private static final Logger LOGGER = Logger.getLogger(EC2UnixLauncher.class.getName());

    private TaskListener listener;

    public EC2Logger(TaskListener listener) {
        this.listener = listener;
    }

    public void warn(String message) {
        EC2Cloud.log(LOGGER, Level.WARNING, listener, message);
    }

    public void info(String message) {
        EC2Cloud.log(LOGGER, Level.INFO, listener, message);
    }

    public void exception(String message, Throwable ex) {
        EC2Cloud.log(LOGGER, Level.WARNING, listener, message, ex);
    }
}
