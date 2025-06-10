package hudson.plugins.ec2;

import hudson.model.Node;
import java.util.ArrayList;
import java.util.Collections;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ec2.model.InstanceType;

// A mock ec2 computer returning the data we want
public class MockEC2Computer extends EC2Computer {
    private static final String COMPUTER_NAME = "MockInstanceForTest";

    private InstanceState state = InstanceState.PENDING;

    private String console = null;

    private final EC2AbstractSlave slave;

    public MockEC2Computer(EC2AbstractSlave slave) {
        super(slave);
        this.slave = slave;
    }

    // Create a computer
    public static MockEC2Computer createComputer(String suffix) throws Exception {
        final EC2AbstractSlave slave =
                new EC2AbstractSlave(
                        COMPUTER_NAME + suffix,
                        "id" + suffix,
                        "description" + suffix,
                        "fs",
                        1,
                        null,
                        "label",
                        null,
                        null,
                        "init",
                        "tmpDir",
                        new ArrayList<>(),
                        "remote",
                        EC2AbstractSlave.DEFAULT_JAVA_PATH,
                        "jvm",
                        false,
                        "idle",
                        null,
                        "cloud",
                        Integer.MAX_VALUE,
                        null,
                        ConnectionStrategy.PRIVATE_IP,
                        -1,
                        Tenancy.Default,
                        EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                        EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                        EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                        EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                        EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED) {
                    @Override
                    public void terminate() {}

                    @Override
                    public String getEc2Type() {
                        return null;
                    }
                };

        return new MockEC2Computer(slave);
    }

    @Override
    public String getDecodedConsoleOutput() throws SdkException {
        return getConsole();
    }

    @Override
    public InstanceState getState() {
        return state;
    }

    @Override
    public EC2AbstractSlave getNode() {
        return slave;
    }

    @Override
    public SlaveTemplate getSlaveTemplate() {
        return new SlaveTemplate(
                "ami-123",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1_LARGE.toString(),
                false,
                "ttt",
                Node.Mode.NORMAL,
                "AMI description",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet-123 subnet-456",
                null,
                null,
                0,
                0,
                null,
                "",
                false,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_DNS,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
    }

    public void setState(InstanceState state) {
        this.state = state;
    }

    public String getConsole() {
        return console;
    }

    public void setConsole(String console) {
        this.console = console;
    }
}
