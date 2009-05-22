package org.jvnet.hudson.ec2.launcher.gui;

import org.jvnet.hudson.ec2.launcher.Launcher;
import org.jvnet.hudson.ec2.launcher.StorageList;
import com.xerox.amazonws.ec2.EC2Exception;
import org.jets3t.service.S3ServiceException;

import javax.xml.bind.JAXBException;
import java.util.prefs.Preferences;

/**
 * State that the wizard builds up.
 *
 * @author Kohsuke Kawaguchi
 */
final class WizardState {
    private StorageList storageList;
    public final Launcher launcher = new Launcher();

    /**
     * Place to store preferences.
     */
    public final Preferences prefs = Preferences.userNodeForPackage(getClass());

    public void setCredential(String accessId, String secretKey) throws EC2Exception, JAXBException, S3ServiceException {
        launcher.setCredential(accessId, secretKey);
        storageList = new StorageList(accessId,secretKey);
    }

    public StorageList getStorageList() {
        if(storageList==null)
            throw new IllegalStateException("Storage list not yet loaded");
        return storageList;
    }
}
