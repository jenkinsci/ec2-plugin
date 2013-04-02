package hudson.plugins.ec2;

public class EC2SpotRetentionStrategy extends EC2RetentionStrategy {

	public EC2SpotRetentionStrategy(String idleTerminationMinutes) {
		super(idleTerminationMinutes);
	}

	@Override
	public void start(EC2Computer c) {
	    // Do nothing, wait for the slave to connect via JNLP
	}
	

}
