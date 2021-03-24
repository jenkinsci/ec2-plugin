package hudson.plugins.ec2.util;

import hudson.model.Node;
import hudson.plugins.ec2.AMITypeData;
import hudson.plugins.ec2.ConnectionStrategy;
import hudson.plugins.ec2.EC2Tag;
import hudson.slaves.NodeProperty;

import java.util.List;

public abstract class EC2AgentConfig {

    final String name;
    final String description;
    final String remoteFS;
    final int numExecutors;
    final String labelString;
    final Node.Mode mode;
    final String initScript;
    final String tmpDir;
    final List<? extends NodeProperty<?>> nodeProperties;
    final String remoteAdmin;
    final String jvmopts;
    final String idleTerminationMinutes;
    final List<EC2Tag> tags;
    final String cloudName;
    final int launchTimeout;
    final AMITypeData amiType;
    final ConnectionStrategy connectionStrategy;
    final int maxTotalUses;

    private EC2AgentConfig(Builder<? extends Builder, ? extends EC2AgentConfig> builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.remoteFS = builder.remoteFS;
        this.numExecutors = builder.numExecutors;
        this.labelString = builder.labelString;
        this.mode = builder.mode;
        this.initScript = builder.initScript;
        this.tmpDir = builder.tmpDir;
        this.nodeProperties = builder.nodeProperties;
        this.remoteAdmin = builder.remoteAdmin;
        this.jvmopts = builder.jvmopts;
        this.idleTerminationMinutes = builder.idleTerminationMinutes;
        this.tags = builder.tags;
        this.cloudName = builder.cloudName;
        this.launchTimeout = builder.launchTimeout;
        this.amiType = builder.amiType;
        this.connectionStrategy = builder.connectionStrategy;
        this.maxTotalUses = builder.maxTotalUses;
    }

    public static class OnDemand extends EC2AgentConfig {

        final String instanceId;
        final boolean stopOnTerminate;
        final String publicDNS;
        final String privateDNS;
        final boolean useDedicatedTenancy;

        private OnDemand(OnDemandBuilder builder) {
            super(builder);
            this.instanceId = builder.getInstanceId();
            this.stopOnTerminate = builder.isStopOnTerminate();
            this.publicDNS = builder.getPublicDNS();
            this.privateDNS = builder.getPrivateDNS();
            this.useDedicatedTenancy = builder.isUseDedicatedTenancy();
        }
    }

    public static class Spot extends EC2AgentConfig {

        final String spotInstanceRequestId;
        boolean restartSpotInterruption;

        private Spot(SpotBuilder builder) {
            super(builder);
            this.spotInstanceRequestId = builder.spotInstanceRequestId;
            this.restartSpotInterruption = builder.restartSpotInterruption;
        }
    }

    private static abstract class Builder<B extends Builder<B, C>, C extends EC2AgentConfig> {

        private String name;
        private String description;
        private String remoteFS;
        private int numExecutors;
        private String labelString;
        private Node.Mode mode;
        private String initScript;
        private String tmpDir;
        private List<? extends NodeProperty<?>> nodeProperties;
        private String remoteAdmin;
        private String jvmopts;
        private String idleTerminationMinutes;
        private List<EC2Tag> tags;
        private String cloudName;
        private int launchTimeout;
        private AMITypeData amiType;
        private ConnectionStrategy connectionStrategy;
        private int maxTotalUses;

        public B withName(String name) {
            this.name = name;
            return self();
        }

        public B withDescription(String description) {
            this.description = description;
            return self();
        }

        public B withRemoteFS(String remoteFS) {
            this.remoteFS = remoteFS;
            return self();
        }

        public B withNumExecutors(int numExecutors) {
            this.numExecutors = numExecutors;
            return self();
        }

        public B withLabelString(String labelString) {
            this.labelString = labelString;
            return self();
        }

        public B withMode(Node.Mode mode) {
            this.mode = mode;
            return self();
        }

        public B withInitScript(String initScript) {
            this.initScript = initScript;
            return self();
        }

        public B withTmpDir(String tmpDir) {
            this.tmpDir = tmpDir;
            return self();
        }

        public B withNodeProperties(List<? extends NodeProperty<?>> nodeProperties) {
            this.nodeProperties = nodeProperties;
            return self();
        }

        public List<? extends NodeProperty<?>> getNodeProperties() {
            return nodeProperties;
        }

        public B withRemoteAdmin(String remoteAdmin) {
            this.remoteAdmin = remoteAdmin;
            return self();
        }

        public B withJvmopts(String jvmopts) {
            this.jvmopts = jvmopts;
            return self();
        }

        public B withIdleTerminationMinutes(String idleTerminationMinutes) {
            this.idleTerminationMinutes = idleTerminationMinutes;
            return self();
        }

        public B withTags(List<EC2Tag> tags) {
            this.tags = tags;
            return self();
        }

        public B withCloudName(String cloudName) {
            this.cloudName = cloudName;
            return self();
        }

        public B withLaunchTimeout(int launchTimeout) {
            this.launchTimeout = launchTimeout;
            return self();
        }

        public B withAmiType(AMITypeData amiType) {
            this.amiType = amiType;
            return self();
        }

        public B withConnectionStrategy(ConnectionStrategy connectionStrategy) {
            this.connectionStrategy = connectionStrategy;
            return self();
        }

        public B withMaxTotalUses(int maxTotalUses) {
            this.maxTotalUses = maxTotalUses;
            return self();
        }

        protected abstract B self();

        public abstract C build();
    }

    public static class OnDemandBuilder extends Builder<OnDemandBuilder, OnDemand> {

        private String instanceId;
        private boolean stopOnTerminate;
        private String publicDNS;
        private String privateDNS;
        private boolean useDedicatedTenancy;

        public OnDemandBuilder withInstanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public OnDemandBuilder withStopOnTerminate(boolean stopOnTerminate) {
            this.stopOnTerminate = stopOnTerminate;
            return this;
        }

        public boolean isStopOnTerminate() {
            return stopOnTerminate;
        }

        public OnDemandBuilder withPublicDNS(String publicDNS) {
            this.publicDNS = publicDNS;
            return this;
        }

        public String getPublicDNS() {
            return publicDNS;
        }

        public OnDemandBuilder withPrivateDNS(String privateDNS) {
            this.privateDNS = privateDNS;
            return this;
        }

        public String getPrivateDNS() {
            return privateDNS;
        }

        public OnDemandBuilder withUseDedicatedTenancy(boolean useDedicatedTenancy) {
            this.useDedicatedTenancy = useDedicatedTenancy;
            return this;
        }

        public boolean isUseDedicatedTenancy() {
            return useDedicatedTenancy;
        }

        @Override
        protected OnDemandBuilder self() {
            return this;
        }

        @Override
        public OnDemand build() {
            return new OnDemand(this);
        }
    }

    public static class SpotBuilder extends Builder<SpotBuilder, Spot> {

        private String spotInstanceRequestId;
        private boolean restartSpotInterruption;

        public SpotBuilder withSpotInstanceRequestId(String spotInstanceRequestId) {
            this.spotInstanceRequestId = spotInstanceRequestId;
            return this;
        }

        public SpotBuilder withRestartSpotInterruption(boolean restartSpotInterruption) {
            this.restartSpotInterruption = restartSpotInterruption;
            return this;
        }

        @Override
        protected SpotBuilder self() {
            return this;
        }

        @Override
        public Spot build() {
            return new Spot(this);
        }
    }

}
