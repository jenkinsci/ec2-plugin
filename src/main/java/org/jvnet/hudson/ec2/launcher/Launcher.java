package org.jvnet.hudson.ec2.launcher;

import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.InstanceType;
import com.xerox.amazonws.ec2.Jec2;
import com.xerox.amazonws.ec2.KeyPairInfo;
import com.xerox.amazonws.ec2.LaunchConfiguration;
import com.xerox.amazonws.ec2.ReservationDescription;
import com.xerox.amazonws.ec2.ReservationDescription.Instance;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Encapsulates the logic of starting a Hudson on EC2 with EBS.
 *
 * @author Kohsuke Kawaguchi
 */
public class Launcher {
    private Jec2 ec2;

    /**
     * Storage to be used by the launched Hudson.
     */
    private Storage storage;

    private PrivateKeyFile privateKey;
    private String privateKeyName;
    private String accessId;
    private String secretKey;
    private ReservationDescription.Instance instance;

    public Jec2 getEc2() {
        return ec2;
    }

    /**
     * Configures the launcher withe the AWS credential.
     *
     * @throws EC2Exception
     *      If the given credential fails, including network connectivity problem.
     */
    public void setCredential(String accessId, String secretKey) throws EC2Exception {
        this.accessId = accessId;
        this.secretKey = secretKey;
        ec2 = new Jec2(accessId, secretKey);
        // to validate the credential, make a harmless request
        ec2.describeInstances(Collections.<String>emptyList());
    }

    public String getAccessId() {
        return accessId;
    }

    public String getSecretKey() {
        return secretKey;
    }

    /**
     * Sets the private key used for launching EC2 instances.
     */
    public void setPrivateKey(File keyFile) throws IOException, EC2Exception, OperatorErrorException {
        if(!keyFile.exists())
            throw new OperatorErrorException("No such file exists: "+keyFile);
        PrivateKeyFile pk = new PrivateKeyFile(keyFile);
        if(!pk.isPrivateKey())
            throw new OperatorErrorException("Not a valid RSA private key file: "+keyFile);

        String fingerprint = pk.getFingerprint();
        for(KeyPairInfo k : ec2.describeKeyPairs(Collections.<String>emptyList())) {
            if(k.getKeyFingerprint().equalsIgnoreCase(fingerprint)) {
                // found a corresponding key on EC2
                privateKeyName = k.getKeyName();
                privateKey = pk;
                return;
            }
        }
        throw new OperatorErrorException("This key is not registered in EC2. You probably didn't create this key with ec2-add-keypair");
    }

    public PrivateKeyFile getPrivateKey() {
        if(privateKey==null)
            throw new IllegalStateException("private key is not set yet");
        return privateKey;
    }

    /**
     * Chooses the storage to be used for Hudson.
     */
    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Storage getStorage() {
        if(storage==null)
            throw new IllegalStateException("storage is not set yet");
        return storage;
    }

    public Instance getInstance() {
        if(instance==null)
            throw new IllegalStateException("instance is not set yet");
        return instance;
    }

    /**
     * Starts the EC2 instance asynchronously.
     */
    public ReservationDescription.Instance start() throws EC2Exception, OperatorErrorException {
        // figure out the availability zone to run this in

        // TODO: need a custom AMI
        LaunchConfiguration lc = new LaunchConfiguration("ami-7db75014");
        lc.setMinCount(1);
        lc.setMaxCount(1);
        // TODO: what about the security group?
        lc.setSecurityGroup(Collections.<String>emptyList());
        // TODO: set the real user data
//        lc.setUserData(userData.getBytes());
        lc.setKeyName(privateKeyName);
        lc.setInstanceType(InstanceType.DEFAULT);
        // needs to launch this on the same zone as the storage is
        lc.setAvailabilityZone(storage.getAvailabilityZone(ec2));
        ReservationDescription rd = ec2.runInstances(lc);
        instance = rd.getInstances().get(0);
        return instance;
    }

    public InstanceState checkBootStatus(ReservationDescription.Instance inst) throws EC2Exception {
        List<ReservationDescription> r = ec2.describeInstances(Collections.singletonList(inst.getInstanceId()));
        if(r.isEmpty()) return InstanceState.TERMINATED; // be defensive
        List<ReservationDescription.Instance> insts = r.get(0).getInstances();
        if(insts.isEmpty()) return InstanceState.TERMINATED;
        return InstanceState.parse(insts.get(0));
    }
}
