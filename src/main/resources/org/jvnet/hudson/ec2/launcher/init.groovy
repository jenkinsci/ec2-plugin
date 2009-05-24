import hudson.model.*;
import hudson.security.*;

h = Hudson.getInstance();
ucl=h.pluginManager.uberClassLoader;

// since EC2 instances are publicly accessible,
// we need Hudson to start in a password protected manner
h.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy());
HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false);

accesskey=new File("/root/accessId.txt").text.trim();
password =new File("/root/secretKey.txt").text.trim();
key=new File("/root/ec2.key").text.trim();

realm.createAccount(accesskey,password);
h.setSecurityRealm(realm);

// configure EC2 plugin
h.clouds.add(ucl.loadClass("hudson.plugins.ec2.EC2Cloud").newInstance(accesskey,password,key,"",[]));

h.save();

// delete the init file once it's completed
new File(h.getRootDir(),"init.groovy").delete();
