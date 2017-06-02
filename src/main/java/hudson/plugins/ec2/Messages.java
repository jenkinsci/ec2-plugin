package hudson.plugins.ec2;


import org.jvnet.localizer.Localizable;
import org.jvnet.localizer.ResourceBundleHolder;

public class Messages {
    private static final ResourceBundleHolder holder = ResourceBundleHolder.get(Messages.class);

    public Messages() {
    }

    public static String EC2Cloud_Success() {
        return holder.format("EC2Cloud.Success", new Object[0]);
    }

    public static Localizable _EC2Cloud_Success() {
        return new Localizable(holder, "EC2Cloud.Success", new Object[0]);
    }

    public static String EC2OndemandSlave_AmazonEC2() {
        return holder.format("EC2OndemandSlave.AmazonEC2", new Object[0]);
    }

    public static Localizable _EC2OndemandSlave_AmazonEC2() {
        return new Localizable(holder, "EC2OndemandSlave.AmazonEC2", new Object[0]);
    }

    public static String EC2OndemandSlave_OnDemand() {
        return holder.format("EC2OndemandSlave.OnDemand", new Object[0]);
    }

    public static Localizable _EC2OndemandSlave_OnDemand() {
        return new Localizable(holder, "EC2OndemandSlave.OnDemand", new Object[0]);
    }

    public static String EC2SpotSlave_AmazonEC2SpotInstance() {
        return holder.format("EC2SpotSlave.AmazonEC2SpotInstance", new Object[0]);
    }

    public static Localizable _EC2SpotSlave_AmazonEC2SpotInstance() {
        return new Localizable(holder, "EC2SpotSlave.AmazonEC2SpotInstance", new Object[0]);
    }

    public static String EC2SpotSlave_Spot2() {
        return holder.format("EC2SpotSlave.Spot2", new Object[0]);
    }

    public static Localizable _EC2SpotSlave_Spot2() {
        return new Localizable(holder, "EC2SpotSlave.Spot2", new Object[0]);
    }

    public static String EC2SpotSlave_Spot1() {
        return holder.format("EC2SpotSlave.Spot1", new Object[0]);
    }

    public static Localizable _EC2SpotSlave_Spot1() {
        return new Localizable(holder, "EC2SpotSlave.Spot1", new Object[0]);
    }

    public static String EC2Cloud_FailedToObtainCredentailsFromEC2() {
        return holder.format("EC2Cloud.FailedToObtainCredentailsFromEC2", new Object[0]);
    }

    public static Localizable _EC2Cloud_FailedToObtainCredentailsFromEC2() {
        return new Localizable(holder, "EC2Cloud.FailedToObtainCredentailsFromEC2", new Object[0]);
    }

    public static String AmazonEC2Cloud_NonUniqName() {
        return holder.format("AmazonEC2Cloud.NonUniqName", new Object[0]);
    }

    public static Localizable _AmazonEC2Cloud_NonUniqName() {
        return new Localizable(holder, "AmazonEC2Cloud.NonUniqName", new Object[0]);
    }
}
