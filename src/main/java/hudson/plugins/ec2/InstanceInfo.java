package hudson.plugins.ec2;

import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.KeyPair;

import java.util.List;
import java.util.stream.Collectors;

public class InstanceInfo {
    private Instance instance;
    private KeyPair keypair;

    public InstanceInfo(Instance instance, KeyPair keypair) {
        this.instance = instance;
        this.keypair = keypair;
    }

    public Instance getInstance() {
        return instance;
    }

    public KeyPair getKeypair() {
        return keypair;
    }

    public static List<InstanceInfo> fromInstances(List<Instance> instances) {
        return instances.stream().map(obj -> new InstanceInfo(obj, null)).collect(Collectors.toList());
    }
}
