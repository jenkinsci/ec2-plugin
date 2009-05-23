package org.jvnet.hudson.ec2.launcher;

import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.VolumeInfo;
import com.xerox.amazonws.ec2.Jec2;
import org.jets3t.service.S3ServiceException;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.security.AWSCredentials;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.annotation.XmlElementRef;
import javax.xml.bind.annotation.XmlRootElement;
import javax.swing.*;
import javax.swing.event.ListDataListener;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Collections;
import java.util.logging.Logger;
import static java.util.logging.Level.INFO;

/**
 * View of the Hudson storage list configured for the current account.
 *
 * @author Kohsuke Kawaguchi
 */
@XmlRootElement
public class StorageList implements Iterable<Storage> {
    @XmlElementRef
    private final List<Storage> storages = new ArrayList<Storage>();

    private transient final RestS3Service s3;

    private transient String accessId;

    public StorageList(String accessId, String secretKey) throws S3ServiceException, JAXBException, EC2Exception {
        s3 = new RestS3Service(new AWSCredentials(accessId,secretKey));
        this.accessId = accessId;

        // load the storage list from S3
        S3Bucket b = s3.getBucket(getBucketName());
        if(b!=null) {
            try {
                StorageList src = (StorageList) JAXB.createUnmarshaller().unmarshal(s3.getObject(b, "storages.xml").getDataInputStream());
                storages.addAll(src.storages);
            } catch (S3ServiceException e) {
                // no storages.xml found. nothing to load
                LOGGER.log(INFO,"No storages.xml. Assuming there's no storage",e);
            }
        }

        // remove storages that no longer exist
        Jec2 ec2 = new Jec2(accessId,secretKey);
        List<VolumeInfo> volInfos = ec2.describeVolumes(Collections.<String>emptyList());
        for (Iterator<Storage> itr = storages.iterator(); itr.hasNext();) {
            Storage s =  itr.next();
            if(!s.isAllVolumesPresent(volInfos))
                itr.remove();
        }
    }

    // for persistence
    private StorageList() {
        s3 = null;
    }

    public Iterator<Storage> iterator() {
        return storages.iterator();
    }

    public int size() {
        return storages.size();
    }

    /**
     * Creates a new storage.
     */
    public Storage create(Launcher l, String name, int sizeInGB) throws EC2Exception, JAXBException, S3ServiceException {
        VolumeInfo v = l.getEc2().createVolume(String.valueOf(sizeInGB), null, "us-east-1b");
        Storage s = new Storage(name, v.getVolumeId());
        storages.add(s);
        save();
        return s;
    }

    public Storage get(String name) {
        for (Storage s : storages) {
            if(s.name.equals(name))
                return s;
        }
        return null;
    }

    /**
     * Persists the storage list to S3.
     */
    private void save() throws S3ServiceException, JAXBException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JAXB.createMarshaller().marshal(this,baos);

        S3Bucket b = s3.getBucket(getBucketName());
        if(b==null)
            b = s3.createBucket(getBucketName());
        S3Object s3o = new S3Object(b, "storages.xml");
        s3o.setDataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        s3o.setContentType("application/xml; charset=UTF-8");

        s3.putObject(b,s3o);
    }

    private String getBucketName() {
        return "hudson-"+accessId;
    }

    public ListModel asListModel() {
        return new ListModel() {
            public int getSize() {
                return storages.size();
            }

            public Object getElementAt(int index) {
                return storages.get(index);
            }

            public void addListDataListener(ListDataListener l) {
            }

            public void removeListDataListener(ListDataListener l) {
            }
        };
    }

    private static final JAXBContext JAXB;

    static {
        try {
            JAXB = JAXBContext.newInstance(StorageList.class);
        } catch (JAXBException e) {
            throw new AssertionError(e); // impossible
        }
    }

    private static final Logger LOGGER = Logger.getLogger(StorageList.class.getName());
}
