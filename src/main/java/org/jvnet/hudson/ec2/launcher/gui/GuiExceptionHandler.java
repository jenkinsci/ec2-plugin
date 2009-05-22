package org.jvnet.hudson.ec2.launcher.gui;

import javax.swing.*;
import java.awt.*;

/**
 * @author Kohsuke Kawaguchi
 */
public class GuiExceptionHandler {
    public void handle(Throwable t) {
        t.printStackTrace();
        JOptionPane.showMessageDialog(findActiveFrame(),
                t.toString(), "Exception Occurred", JOptionPane.ERROR_MESSAGE);
    }

    private Frame findActiveFrame() {
        for (Frame frame : JFrame.getFrames())
            if (frame.isVisible())
                return frame;
        return null;
    }
}
