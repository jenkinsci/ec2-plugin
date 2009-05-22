package org.jvnet.hudson.ec2.launcher.gui;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

/**
 * Simplifies event subscriptions so that a single update method gets called
 * regardless of the component that changes.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class CompletionUpdater {
    /**
     * Called for a change in the any of the component.
     */
    public abstract void update();

    protected boolean has(JTextField field) {
        return field.getText().length()>0;
    }

    public CompletionUpdater add(JRadioButton r) {
        r.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent e) {
                update();
            }
        });
        return this;
    }

    public CompletionUpdater add(JTextField text) {
        text.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void change(DocumentEvent e) {
                update();
            }
        });
        return this;
    }

    public CompletionUpdater add(JList list) {
        list.addListSelectionListener(new ListSelectionListener() {
            public void valueChanged(ListSelectionEvent e) {
                update();
            }
        });
        return this;
    }
}
