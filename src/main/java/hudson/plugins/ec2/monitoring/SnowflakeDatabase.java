package hudson.plugins.ec2.monitoring;

import hudson.Extension;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import org.jenkinsci.plugins.database.BasicDataSource2;
import org.jenkinsci.plugins.database.DatabaseDescriptor;
import org.jenkinsci.plugins.database.Database;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;
import com.snowflake.client.jdbc.SnowflakeDriver;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.security.ACL;

/**
 * Snowflake database configuration for EC2 provisioning monitoring.
 * Based on the snowflake-jenkins-connector implementation.
 */
public class SnowflakeDatabase extends Database {

    private static final Logger LOG = Logger.getLogger(SnowflakeDatabase.class.getName());
    private transient DataSource source;

    public final String accountname;
    public final String database;
    public final String networktimeout;
    public final String querytimeout;
    public final String logintimeout;
    public final String warehouse;
    public final String credentialsId;

    @DataBoundConstructor
    public SnowflakeDatabase(String accountname,
                            String database,
                            String warehouse,
                            String credentialsId,
                            String networktimeout,
                            String querytimeout,
                            String logintimeout) {

        this.accountname = accountname;
        this.database = database;
        this.warehouse = warehouse;
        this.credentialsId = credentialsId;
        this.networktimeout = networktimeout;
        this.querytimeout = querytimeout;
        this.logintimeout = logintimeout;
    }

    protected Class<? extends Driver> getDriverClass() {
        return SnowflakeDriver.class;
    }

    @Override
    public synchronized DataSource getDataSource() throws SQLException {
        List credentialList = CredentialsProvider.lookupCredentials(
            StandardUsernamePasswordCredentials.class, Jenkins.getInstanceOrNull(), ACL.SYSTEM,
            Collections.<DomainRequirement>emptyList());

        StandardUsernamePasswordCredentials credentials = (StandardUsernamePasswordCredentials)CredentialsMatchers.firstOrNull(credentialList,
          CredentialsMatchers.allOf(CredentialsMatchers.withId(credentialsId)));

        if (source==null) {
            BasicDataSource2 fac = new BasicDataSource2();
            fac.setDriverClass(getDriverClass());
            fac.setUrl(getJdbcUrl());
            fac.setUsername(credentials.getUsername());
            fac.setPassword(Secret.toString(credentials.getPassword()));
            fac.setValidationQuery("SELECT 1");

            source = fac.createDataSource();
        }
        return source;
    }

    protected String getJdbcUrl() {
        String url = "jdbc:snowflake://"+this.accountname+
            ".snowflakecomputing.com/?db="+this.database+
            "&networkTimeout="+this.networktimeout+
            "&queryTimeout="+this.querytimeout+
            "&warehouse="+this.warehouse+
            "&loginTimeout="+this.logintimeout;
        LOG.log(Level.FINE, "JDBC URL {0}", url);
        return url;
    }

    public String fetchJdbcUrl() {
        return getJdbcUrl();
    }

    @Extension
    public static class DescriptorImpl extends DatabaseDescriptor {
        @Override
        public String getDisplayName() {
            return "Snowflake EC2 Monitoring";
        }

        public FormValidation doValidateSnowflake(
            @QueryParameter String accountname,
            @QueryParameter String database,
            @QueryParameter String warehouse,
            @QueryParameter String credentialsId,
            @QueryParameter String networktimeout,
            @QueryParameter String querytimeout,
            @QueryParameter String logintimeout)
                throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, InstantiationException {

            DataSource ds;
            Connection con = null;
            Statement s = null;
            try {
                Database db = clazz.getConstructor(String.class,
                                                String.class,
                                                String.class,
                                                String.class,
                                                String.class,
                                                String.class,
                                                String.class).newInstance(accountname,
                                                                        database,
                                                                        warehouse,
                                                                        credentialsId,
                                                                        networktimeout,
                                                                        querytimeout,
                                                                        logintimeout);
                ds = db.getDataSource();
                con = ds.getConnection();
                s = con.createStatement();
                s.execute("SELECT 1");
                return FormValidation.ok("OK");
            } catch (SQLException e) {
                return FormValidation.error(e, "Failed to connect to " + getDisplayName());
            } finally {
                try {
                if (s != null)
                    s.close();
                if (con != null)
                    con.close();
                } catch (Exception e) {
                }
            }
        }

        @RequirePOST
        public ListBoxModel doFillCredentialsIdItems() {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withMatching(
                        CredentialsMatchers.always(),
                        CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class,
                                Jenkins.get(),
                                ACL.SYSTEM,
                                Collections.emptyList()));
        }
    }
} 