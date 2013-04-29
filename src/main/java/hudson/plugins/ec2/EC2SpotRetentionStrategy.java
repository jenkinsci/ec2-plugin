package hudson.plugins.ec2;

public class EC2SpotRetentionStrategy extends EC2RetentionStrategy {

	public EC2SpotRetentionStrategy(String idleTerminationMinutes) {
		super(idleTerminationMinutes);
	}

	@Override
	public void start(EC2Computer c) {
		// Do nothing, wait for the slave to connect via JNLP
		// If we try to connect to it right away there is no server
		// to connect to due to the delay in Spot requests being fulfilled
		// By doing nothing we prevent the failed connection error from
		// displaying to the user
	}
}
