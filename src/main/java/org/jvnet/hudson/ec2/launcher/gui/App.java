package org.jvnet.hudson.ec2.launcher.gui;

import org.pietschy.wizard.Wizard;
import org.pietschy.wizard.WizardEvent;
import org.pietschy.wizard.WizardListener;
import org.pietschy.wizard.models.StaticModel;

import javax.swing.*;
import java.awt.*;

/**
 * Main entry point.
 */
public class App
{
    public static void main( String[] args ) throws Exception {
        WizardState ws = new WizardState();

        StaticModel model = new StaticModel();
        model.add(new StartPage(ws));
        model.add(new SelectKeyPage(ws));
        model.add(new SelectEBSPage(ws));
        model.add(new BootPage(ws));

        Wizard wizard = new Wizard(model) {
            @Override
            protected JComponent createTitleComponent() {
                ImageIcon background = new ImageIcon(getClass().getResource("title.png"));
                JLabel backgroundImage = new JLabel(background);
                backgroundImage.setVerticalAlignment(SwingConstants.BOTTOM);
                backgroundImage.setHorizontalAlignment(SwingConstants.RIGHT);

                JPanel panel = new JPanel(new BorderLayout());
                panel.setBackground(Color.WHITE);
                panel.add(backgroundImage);
                panel.setBorder(BorderFactory.createEtchedBorder());
                return panel;
            }
        };
        wizard.setOverviewVisible(false);
        wizard.showInFrame("Hudson EC2 Wizard");
        wizard.addWizardListener(new WizardListener() {
            public void wizardClosed(WizardEvent e) {
                System.exit(0);
            }

            public void wizardCancelled(WizardEvent e) {
                System.exit(0);
            }
        });
    }

    static {
        // Sets the native look and feel, if possible.
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (InstantiationException e) {
        } catch (ClassNotFoundException e) {
        } catch (UnsupportedLookAndFeelException e) {
        } catch (IllegalAccessException e) {
        }

        // install the exception event handler
        if(System.getProperty("sun.awt.exception.handler")==null)
            System.setProperty("sun.awt.exception.handler", GuiExceptionHandler.class.getName());
    }
}
