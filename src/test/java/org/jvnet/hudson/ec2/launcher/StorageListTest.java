package org.jvnet.hudson.ec2.launcher;

import junit.framework.TestCase;

import javax.xml.bind.JAXBContext;

import org.jvnet.hudson.ec2.launcher.StorageList;

/**
 * @author Kohsuke Kawaguchi
 */
public class StorageListTest extends TestCase {
    /**
     * Makes sure that the classes are bindable.
     */
    public void testJAXB() throws Exception {
        JAXBContext.newInstance(StorageList.class);
    }
}
