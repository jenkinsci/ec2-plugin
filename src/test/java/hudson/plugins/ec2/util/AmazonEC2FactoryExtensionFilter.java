package hudson.plugins.ec2.util;

import hudson.Extension;
import hudson.ExtensionComponent;
import jenkins.ExtensionFilter;

/**
 * The sole purpose of this class is to filter out {@link AmazonEC2FactoryImpl} so as to avoid doing real calls to EC2
 * when running tests.
 */
@Extension
public class AmazonEC2FactoryExtensionFilter extends ExtensionFilter {
    @Override
    public <T> boolean allows(Class<T> type, ExtensionComponent<T> component) {
        if (!type.isAssignableFrom(AmazonEC2Factory.class)) {
            return true;
        }
        return !(component.getInstance().getClass().isAssignableFrom(AmazonEC2FactoryImpl.class));
    }
}
