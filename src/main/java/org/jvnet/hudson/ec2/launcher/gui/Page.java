package org.jvnet.hudson.ec2.launcher.gui;

import org.jvnet.hudson.ec2.launcher.Launcher;
import org.pietschy.wizard.PanelWizardStep;

import javax.swing.*;
import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class Page extends PanelWizardStep {
    public final WizardState state;
    public final Launcher launcher;

    protected Page(WizardState state) {
        this.state = state;
        this.launcher = state.launcher;

        setLayout(new BorderLayout());
    }

    protected void busyCursor() {
        getTopLevelAncestor().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    }

    protected void restoreCursor() {
        getTopLevelAncestor().setCursor(Cursor.getDefaultCursor());
    }

    public void reportError(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        sw.write(msg);
        if(t!=null) {
            sw.write("\n");
            t.printStackTrace(new PrintWriter(sw));
        }
        JOptionPane.showMessageDialog(this,sw,"Error",JOptionPane.ERROR_MESSAGE);
    }
}
