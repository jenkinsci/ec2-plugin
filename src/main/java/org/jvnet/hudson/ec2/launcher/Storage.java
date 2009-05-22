package org.jvnet.hudson.ec2.launcher;

import com.xerox.amazonws.ec2.Jec2;
import com.xerox.amazonws.ec2.VolumeInfo;
import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.AttachmentInfo;
import com.xerox.amazonws.ec2.ReservationDescription.Instance;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

/**
 * @author Kohsuke Kawaguchi
 */
@XmlRootElement
public class Storage {
    /**
     * Name of this storage.
     */
    @XmlAttribute
    public final String name;

    /**
     * EBS volumes IDs that are used as a single ZFS pool.
     */
    @XmlAttribute
    public final List<String> volumes;

    public Storage(String name, String... volumes) {
        this.name = name;
        this.volumes = Arrays.asList(volumes);
    }

    private Storage() {
        // this constructor is for JAXB. it will overwrite fields
        this.name = null;
        this.volumes = null;
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * Determines the availability zone that this storage set lives on.
     *
     * For instances to attach to this storage, it needs to run on this zone.
     */
    public String getAvailabilityZone(Jec2 ec2) throws EC2Exception, OperatorErrorException {
        VolumeInfo ref=null;
        for(VolumeInfo vi : ec2.describeVolumes(volumes)) {
            if(ref==null)  ref=vi;
            if(!ref.getZone().equals(vi.getZone())) {
                throw new OperatorErrorException("EBS volume "+ref.getVolumeId()+" and "+vi.getVolumeId()+" are on different availability zones.");
            }
        }
        return ref.getZone();
    }

    /**
     * Attaches all the volumes, and returns the device names.
     */
    public List<String> attach(Instance inst, Jec2 ec2) throws EC2Exception, OperatorErrorException, InterruptedException {
        int device = 5;
        List<String> r = new ArrayList<String>();
        for (String id : volumes) {
            r.add("/dev/dsk/c3d"+device);
            ec2.attachVolume(id,inst.getInstanceId(),String.valueOf(device++));
        }
        // block until they are all attached
        boolean attached;
        int cnt=0;
        do {
            attached = true;
            cnt++;
            Thread.sleep(1000);
            for(VolumeInfo vi : ec2.describeVolumes(volumes)) {
                List<AttachmentInfo> ai = vi.getAttachmentInfo();
                if(ai==null || ai.size()==0) {
                    if(cnt>20)
                        throw new OperatorErrorException("EBS volume "+vi.getVolumeId()+" appears to have failed to attach");
                    attached = false;
                    break;
                }
                String st = ai.get(0).getStatus();
                if(st==null)    st="";
                if(st.equals("attaching")) {
                    attached = false;
                    break;
                }
                if(!st.equals("attached"))
                    throw new OperatorErrorException("EBS volume "+vi.getVolumeId()+" reported its status as "+st);
            }
        } while(!attached);
        
        return r;
    }
}
