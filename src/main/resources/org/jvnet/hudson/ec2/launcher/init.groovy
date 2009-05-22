import hudson.model.*;
import hudson.security.*;

// since EC2 instances are publicly accessible,
// we need Hudson to start in a password protected manner
Hudson h = Hudson.getInstance();
h.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy());
HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false);

accesskey=new File("/root/accessId.txt").text.trim();
password =new File("/root/secretKey.txt").text.trim();
realm.createAccount(accesskey,password);
h.setSecurityRealm(realm);
h.save();

// delete the init file once it's completed
new File(h.getRootDir(),"init.groovy").delete();
