package hudson.plugins.ec2;

import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.KeyPair;

import java.util.ArrayList;
import java.util.List;

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
        List<InstanceInfo> result = new ArrayList<>();
        instances.stream().forEach(obj -> { result.add(new InstanceInfo(obj, null));});
        return result;
    }
}
