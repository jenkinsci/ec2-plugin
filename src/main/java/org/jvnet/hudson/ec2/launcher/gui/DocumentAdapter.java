package org.jvnet.hudson.ec2.launcher.gui;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * {@link DocumentListener} adapter.
 * 
 * @author Kohsuke Kawaguchi
 */
class DocumentAdapter implements DocumentListener {
    public void insertUpdate(DocumentEvent e) {
        change(e);
    }

    public void removeUpdate(DocumentEvent e) {
        change(e);
    }

    public void changedUpdate(DocumentEvent e) {
        change(e);
    }

    protected void change(DocumentEvent e) {
    }
}
