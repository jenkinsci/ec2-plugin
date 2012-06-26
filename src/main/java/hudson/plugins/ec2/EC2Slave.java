package hudson.plugins.ec2;

import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Slave;
import hudson.model.Node;
import hudson.plugins.ec2.ssh.EC2UnixLauncher;
import hudson.slaves.NodeProperty;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.LinkedList;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.*;
import net.sf.json.JSONObject;

/**
 * Slave running on EC2.
 * 
 * @author Kohsuke Kawaguchi
 */
public final class EC2Slave extends Slave {
    /**
     * Comes from {@link SlaveTemplate#initScript}.
     */
    public final String initScript;
    public final String remoteAdmin; // e.g. 'ubuntu'
    public final String rootCommandPrefix; // e.g. 'sudo'
    public final String jvmopts; //e.g. -Xmx1g
    public final boolean stopOnTerminate;
    public final String idleTerminationMinutes;

    // Temporary stuff that is obtained live from EC2
    public String publicDNS;
    public String privateDNS;
    public List<EC2Tag> tags;
    public final boolean usePrivateDnsName;

    private long _last_live_fetch = 0;

    /* 20 seconds is our polling time for refreshing EC2 data that may change externally. */
    private static final long POLL_PERIOD = 20 * 1000;


    /**
     * For data read from old Hudson, this is 0, so we use that to indicate 22.
     */
    private final int sshPort;

    public static final String TEST_ZONE = "testZone";
    
    public EC2Slave(String instanceId, String description, String remoteFS, int sshPort, int numExecutors,
                    String labelString, String initScript, String remoteAdmin, String rootCommandPrefix, String jvmopts,
                    boolean stopOnTerminate, String idleTerminationMinutes, String publicDNS, String privateDNS, List<EC2Tag> tags)
                    throws FormException, IOException {

        this(instanceId, description, remoteFS, sshPort, numExecutors, Mode.NORMAL, labelString, initScript,
             Collections.<NodeProperty<?>>emptyList(), remoteAdmin, rootCommandPrefix, jvmopts, stopOnTerminate,
             idleTerminationMinutes, publicDNS, privateDNS, tags, false );
    }


    public EC2Slave(String instanceId, String description, String remoteFS, int sshPort, int numExecutors,
                    String labelString, String initScript, String remoteAdmin, String rootCommandPrefix, String jvmopts,
                    boolean stopOnTerminate, String idleTerminationMinutes, String publicDNS, String privateDNS, List<EC2Tag> tags,
                    boolean usePrivateDnsName)
                    throws FormException, IOException {

        this(instanceId, description, remoteFS, sshPort, numExecutors, Mode.NORMAL, labelString, initScript,
             Collections.<NodeProperty<?>>emptyList(), remoteAdmin, rootCommandPrefix, jvmopts, stopOnTerminate,
             idleTerminationMinutes, publicDNS, privateDNS, tags, usePrivateDnsName);
    }

    /*
    public EC2Slave(String instanceId, String description, String remoteFS, int sshPort, int numExecutors, String labelString, String initScript, String remoteAdmin, String rootCommandPrefix, String jvmopts, boolean stopOnTerminate) throws FormException, IOException {
        this(instanceId, description, remoteFS, sshPort, numExecutors, labelString, initScript, remoteAdmin, rootCommandPrefix, jvmopts, stopOnTerminate, false);
    }

    public EC2Slave(String instanceId, String description, String remoteFS, int sshPort, int numExecutors, String labelString, String initScript, String remoteAdmin, String rootCommandPrefix, String jvmopts, boolean stopOnTerminate, boolean usePrivateDnsName) throws FormException, IOException {
        this(instanceId, description, remoteFS, sshPort, numExecutors, Mode.NORMAL, labelString, initScript, Collections.<NodeProperty<?>>emptyList(), remoteAdmin, rootCommandPrefix, jvmopts, stopOnTerminate, usePrivateDnsName);
    }

    public EC2Slave(String instanceId, String description, String remoteFS, int sshPort, int numExecutors, Mode mode, String labelString, String initScript, List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin, String rootCommandPrefix, String jvmopts, boolean stopOnTerminate) throws FormException, IOException {
        this(instanceId, description, remoteFS, sshPort, numExecutors, mode, labelString, initScript, nodeProperties, remoteAdmin, rootCommandPrefix, jvmopts, stopOnTerminate, false);
    }
    */


    @DataBoundConstructor
    public EC2Slave(String instanceId, String description, String remoteFS, int sshPort, int numExecutors, Mode mode,
                    String labelString, String initScript, List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin,
                    String rootCommandPrefix, String jvmopts, boolean stopOnTerminate, String idleTerminationMinutes,
                    String publicDNS, String privateDNS, List<EC2Tag> tags, boolean usePrivateDnsName )
                    throws FormException, IOException {

        super(instanceId, description, remoteFS, numExecutors, mode, labelString, new EC2UnixLauncher(),
              new EC2RetentionStrategy(idleTerminationMinutes), nodeProperties);

        this.initScript  = initScript;
        this.remoteAdmin = remoteAdmin;
        this.rootCommandPrefix = rootCommandPrefix;
        this.jvmopts = jvmopts;
        this.sshPort = sshPort;
        this.stopOnTerminate = stopOnTerminate;
        this.idleTerminationMinutes = idleTerminationMinutes;
        this.publicDNS = publicDNS;
        this.privateDNS = privateDNS;
        this.tags = tags;
        this.usePrivateDnsName = usePrivateDnsName;
    }

    /**
     * Constructor for debugging.
     */
    public EC2Slave(String instanceId) throws FormException, IOException {
        this(instanceId,"debug", "/tmp/hudson", 22, 1, Mode.NORMAL, "debug", "", Collections.<NodeProperty<?>>emptyList(),
             null, null, null, false, null, "Fake public", "Fake private", null, false);
    }

    /**
     * See http://aws.amazon.com/ec2/instance-types/
     */
    /*package*/ static int toNumExecutors(InstanceType it) {
        switch (it) {
        case T1Micro:       return 1;
        case M1Small:       return 1;
        case M1Medium:      return 2;
        case M1Large:       return 4;
        case C1Medium:      return 5;
        case M2Xlarge:      return 6;
        case M1Xlarge:      return 8;
        case M22xlarge:     return 13;
        case C1Xlarge:      return 20;
        case M24xlarge:     return 26;
        case Cc14xlarge:    return 33;
        case Cg14xlarge:    return 33;
        default:            throw new AssertionError();
        }
    }

    /**
     * EC2 instance ID.
     */
    public String getInstanceId() {
        return getNodeName();
    }

    @Override
    public Computer createComputer() {
        return new EC2Computer(this);
    }

    /**
     * Terminates the instance in EC2.
     */
    public void terminate() {
        try {
            AmazonEC2 ec2 = EC2Cloud.get().connect();
            if (stopOnTerminate) {
            	StopInstancesRequest request = new StopInstancesRequest(Collections.singletonList(getInstanceId()));
            	ec2.stopInstances(request);
                LOGGER.info("Terminated EC2 instance (stopped): "+getInstanceId());
            } else {
            	TerminateInstancesRequest request = new TerminateInstancesRequest(Collections.singletonList(getInstanceId()));
            	ec2.terminateInstances(request);
                LOGGER.info("Terminated EC2 instance (terminated): "+getInstanceId());
            }
            Hudson.getInstance().removeNode(this);
        } catch (AmazonClientException e) {
            LOGGER.log(Level.WARNING,"Failed to terminate EC2 instance: "+getInstanceId(),e);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,"Failed to terminate EC2 instance: "+getInstanceId(),e);
        }
    }

    String getRemoteAdmin() {
        if (remoteAdmin == null || remoteAdmin.length() == 0)
            return "root";
        return remoteAdmin;
    }

    String getRootCommandPrefix() {
        if (rootCommandPrefix == null || rootCommandPrefix.length() == 0)
            return "";
        return rootCommandPrefix + " ";
    }

    String getJvmopts() {
        return Util.fixNull(jvmopts);
    }

    public int getSshPort() {
        return sshPort!=0 ? sshPort : 22;
    }

    public boolean getStopOnTerminate() {
        return stopOnTerminate;
    }

    /* Much of the EC2 data is beyond our direct control, therefore we need to refresh it from time to
       time to ensure we reflect the reality of the instances. */
    private void _fetchLiveInstanceData( boolean force ) throws AmazonClientException
    {
		/* If we've grabbed the data recently, don't bother getting it again unless we are forced */
        long now = System.currentTimeMillis();
        if (( _last_live_fetch > 0 ) && ( now - _last_live_fetch < POLL_PERIOD ) && !force )
        {
            return;
        }

        _last_live_fetch = now;

        DescribeInstancesRequest request = new DescribeInstancesRequest();
    	request.setInstanceIds( Collections.<String>singletonList( getNodeName() ));
        Instance i = EC2Cloud.get().connect().describeInstances( request ).getReservations().get(0).getInstances().get(0);
        publicDNS = i.getPublicDnsName();
        privateDNS = i.getPrivateIpAddress();
        tags = new LinkedList<EC2Tag>();

        for ( Tag t : i.getTags() )
        {
            tags.add( new EC2Tag( t.getKey(), t.getValue() ));
        }
    }


	/* Clears all existing tag data so that we can force the instance into a known state */
    private void _clearLiveInstancedata() throws AmazonClientException
    {
        DescribeInstancesRequest request = new DescribeInstancesRequest();
        request.setInstanceIds( Collections.<String>singletonList( getNodeName() ));
        Instance inst = EC2Cloud.get().connect().describeInstances( request ).getReservations().get(0).getInstances().get(0);

        /* Now that we have our instance, we can clear the tags on it */
        if ( !tags.isEmpty() ) {
            HashSet<Tag> inst_tags = new HashSet<Tag>();

            for( EC2Tag t : tags ) {
                inst_tags.add( new Tag( t.getName(), t.getValue()) );
            }

            DeleteTagsRequest tag_request = new DeleteTagsRequest();
            tag_request.withResources( inst.getInstanceId() ).setTags( inst_tags );
            EC2Cloud.get().connect().deleteTags( tag_request );
        }
    }


    /* Sets tags on an instance.  This will not clear existing tag data, so call _clearLiveInstancedata if needed */
    private void _pushLiveInstancedata() throws AmazonClientException
    {
        DescribeInstancesRequest request = new DescribeInstancesRequest();
        request.setInstanceIds( Collections.<String>singletonList( getNodeName() ));
        Instance inst = EC2Cloud.get().connect().describeInstances( request ).getReservations().get(0).getInstances().get(0);

        /* Now that we have our instance, we can set tags on it */
        if ( !tags.isEmpty() ) {
            HashSet<Tag> inst_tags = new HashSet<Tag>();

            for( EC2Tag t : tags ) {
                inst_tags.add( new Tag( t.getName(), t.getValue()) );
            }            

            CreateTagsRequest tag_request = new CreateTagsRequest();
            tag_request.withResources( inst.getInstanceId() ).setTags( inst_tags );
            EC2Cloud.get().connect().createTags( tag_request );
        }
    }

    public String getPublicDNS()
    {
        _fetchLiveInstanceData( publicDNS == null );
        return publicDNS;
    }

    public String getPrivateDNS()
    {
        _fetchLiveInstanceData( privateDNS == null );
        return privateDNS;
    }

    public List<EC2Tag> getTags()
    {
        _fetchLiveInstanceData( false );
        return Collections.unmodifiableList( tags );
    }

    public Node reconfigure( final StaplerRequest req, JSONObject form ) throws FormException
    {
        if ( form == null )
        {
            return null;
        }

        Node result = super.reconfigure( req, form );

        /* Get rid of the old tags, as represented by ourselves */
        _fetchLiveInstanceData( true );
        _clearLiveInstancedata();

        /* Set the new tags, as represented by our successor */
        ((EC2Slave) result)._pushLiveInstancedata();

        return result;
    }


    public boolean getUsePrivateDnsName() {
        return usePrivateDnsName;
    }

	public static ListBoxModel fillZoneItems(String accessId, String secretKey, String region) throws IOException, ServletException {
		ListBoxModel model = new ListBoxModel();
		if (AmazonEC2Cloud.testMode) {
			model.add(TEST_ZONE);
			return model;
		}
			
		if (!StringUtils.isEmpty(accessId) && !StringUtils.isEmpty(secretKey) && !StringUtils.isEmpty(region)) {
			AmazonEC2 client = AmazonEC2Cloud.connect(accessId, secretKey, AmazonEC2Cloud.getEc2EndpointUrl(region));
			DescribeAvailabilityZonesResult zones = client.describeAvailabilityZones();
			List<AvailabilityZone> zoneList = zones.getAvailabilityZones();
			model.add("<not specified>", "");
			for (AvailabilityZone z : zoneList) {
				model.add(z.getZoneName(), z.getZoneName());
			}
		}
		return model;
	}
    
    
    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {
        @Override
		public String getDisplayName() {
            return "Amazon EC2";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }

        public ListBoxModel doFillZoneItems(@QueryParameter String accessId,
        		@QueryParameter String secretKey, @QueryParameter String region) throws IOException,
    			ServletException {
        	return fillZoneItems(accessId, secretKey, region);
    	}
    }

    private static final Logger LOGGER = Logger.getLogger(EC2Slave.class.getName());
}
