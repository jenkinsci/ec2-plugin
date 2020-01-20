# ec2-plugin

[![Jenkins](https://ci.jenkins.io/job/Plugins/job/ec2-plugin/job/master/badge/icon)](https://ci.jenkins.io/job/Plugins/job/ec2-plugin/job/master/)
[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/ec2.svg)](https://plugins.jenkins.io/ec2)
[![Gitter](https://badges.gitter.im/ec2-plugin/Lobby.svg)](https://gitter.im/ec2-plugin/Lobby?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

This is a Jenkins plugin to support ephemeral Jenkins agents on [Amazon EC2](https://aws.amazon.com/ec2/) or other EC2-compatible clouds.
Check the [wiki](https://wiki.jenkins.io/display/JENKINS/Amazon+EC2+Plugin) for documentation and usage examples.
Please report any issues to the [Jenkins Jira](https://issues.jenkins-ci.org/) with the component field set to `ec2-plugin`.

# Security
## Securing the connection to Unix AMIs
When you set up a template for a *Unix* instance (`Type AMI` field), you can select the strategy used to guarantee the
instance you're connecting to is the expected one. You should use a strong strategy to guarantee that a 
_[man-in-the-middle attack](https://en.wikipedia.org/wiki/Man-in-the-middle_attack)_ cannot be performed.

You can select your strategy under the _Advanced..._ configuration, on the _Host Key Verification Strategy_ field of 
every configured AMI. 

The plugin provides several strategies because each one has its own requirements. So providing more than one allows
 administrators to use the one best fits to their environment. These strategies are:

### Strategies
#### Check New Hard
This strategy checks the SSH host key provided by the instance with the key printed out in the instance console during
the instance initialization. If the key is not found, the plugin **doesn't allow** the connection to the instance to 
guarantee the instance is the right one. If the key is found and it is the same as the one presented by the instance,
then it's saved to be used on future connections, so the console is only checked once.

Requirements:

* The AMI used should print the key used. It's a common behaviour, for example the _Amazon Linux 2_ AMI prints it 
out. You can consult the AMI documentation to figure it out.
* The launch timeout should be long enough to allow the plugin to check the instance console. With this strategy, the
plugin waits for the console to be available, which can take a few minutes. The _Launch Timeout in seconds_ field should
have a number to allow that, for example 600 (10 minutes). By default there is no timeout, so it's safe.

The expected format on the instance console is `algorithm base64-public-key` at the beginning of a line. For example:
```
ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBNFNGfKpPS/UT2jAEa0+9aZneku2a7TVwN+MjGesm65DDGnXPcM9TM9BsiOE+s4Vo6aCT9L/TVrtDFa0hqbnqc8= 
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHm0sVqkjSuaPg8e7zfaKXt3b1hE1tBwFsB18NOWv5ow 
ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDNTngsAxOCpZwt+IBqJSQ9MU2qVNYzP4D5i1OHfIRXCrnAuJ54GtFzZEZqqo4e1e/JqBQOX3ZPsaegbkzl2uq5FzfFcFoYYXg5gL7htlZ1I2k6/2iIBv7CHAjbpXMkH8WoF2C3vZFRMWLs20ikQpED+9m11VejE19+kqJwLMopyAtq+/mCgiv4nw5QWh3rrrEcbgzuxYoMD0t9daqBq1V0lzRqL36ALVySy7oDjr3YzCN+wMXe1I36kv3lSeCHXnhc53ubrBIsRakWLBndHhPqyyAOMEjdby/O/EQ2PR7vBpH5MaseaJwvRRDPQ6qt4sV8lk0tEt9qbdb1prFRB4W1 
```
Recommended for:

This strategy is the most secure. It's recommended for every instance if you can meet the requirements. We recommend,
whenever possible, configuring each AMI with _Stop/Disconnect on Idle Timeout_ to take advantage of the ssh host key
cache allowing next connections to be done faster. 

#### Check New Soft
This strategy checks the SSH host key provided by the instance with the key printed out in the instance console during
the instance initialization. If the key is not found, the plugin **allows** the connection to the instance in order to 
guarantee the instance is the right one. If the key is found and it is the same as the one presented by the instance,
then it's saved to be used on future connections, so the console is only checked once.

Requirements:

* The AMI used may print the key used to guarantee the instance is the right one, but **it's not mandatory**.
* The launch timeout should be long enough to allow the plugin to check the instance console. With this strategy, the
plugin waits for the console to be available, which can take a few minutes. The _Launch Timeout in seconds_ field should
have a number to allow that. For example 600 (10 minutes). By default there is no timeout, so it's safe. If the timeout
expires, the connection is not done.

The expected format on the instance console is `algorithm base64-public-key` at the beginning of a line. For example:
```
ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBNFNGfKpPS/UT2jAEa0+9aZneku2a7TVwN+MjGesm65DDGnXPcM9TM9BsiOE+s4Vo6aCT9L/TVrtDFa0hqbnqc8= 
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHm0sVqkjSuaPg8e7zfaKXt3b1hE1tBwFsB18NOWv5ow 
ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDNTngsAxOCpZwt+IBqJSQ9MU2qVNYzP4D5i1OHfIRXCrnAuJ54GtFzZEZqqo4e1e/JqBQOX3ZPsaegbkzl2uq5FzfFcFoYYXg5gL7htlZ1I2k6/2iIBv7CHAjbpXMkH8WoF2C3vZFRMWLs20ikQpED+9m11VejE19+kqJwLMopyAtq+/mCgiv4nw5QWh3rrrEcbgzuxYoMD0t9daqBq1V0lzRqL36ALVySy7oDjr3YzCN+wMXe1I36kv3lSeCHXnhc53ubrBIsRakWLBndHhPqyyAOMEjdby/O/EQ2PR7vBpH5MaseaJwvRRDPQ6qt4sV8lk0tEt9qbdb1prFRB4W1 
```
Recommended for:

This strategy is the default one for AMIs created with a former version of the plugin. It doesn't break any connection
because the plugin connects to the instance even when the key is not found on the console. The only point to take into
 account is you need to have the right timeout to allow the plugin to get the instance console. This strategy is recommended
when upgrading from a previous version of the plugin. _Check New Hard_ is the safest strategy, so you should
consider migrating to it. We recommend, whenever possible, configuring each AMI with _Stop/Disconnect on Idle Timeout_
 to take advantage of the ssh host key cache allowing next connections to be done faster.

#### Accept New
This strategy doesn't check any key on the console. It accepts the key provided by the instance on the first
connection. Then, the key is saved to be used on future connections to detect a Man-in-the-Middle attack (the host
key has changed).

Requirements:
* N/A

Recommended for:

This strategy is recommended when your AMIs don't print out the host keys on the console. The _Check New Soft_ cannot be
 used, but at least, you can catch a man-in-the-middle attack on further connections to the same instance. If the attack
 was already perpetrated you cannot detect that. Again, the _Check New Hard_ is the safest strategy.

#### Off
This strategy neither checks any key on the console, nor checks future connections to the same instance with a saved
key. It accepts blindly the key provided by the instance on the first and further connections.

Requirements:
* N/A

Recommended for:

This strategy is not recommended because of its lack of security. It is the strategy used for prior versions of the plugin.
 
### New AMIs
The default strategy for every new instance is the _Check New Hard_ one. You can select a strategy per AMI. It's under 
the _Advanced..._ configuration, on the _Host Key Verification Strategy_ field. 

### Upgrade - Existing AMIs
You may upgrade from a Jenkins installation with a former plugin version without this security mechanism. The default
 strategy for every existing instance is the _Check New Soft_ strategy. This guarantees your jobs are not going to stop
 working and improves the situation. We recommend, if possible, upgrading to the _Check New Hard_ strategy to be safer
 against a _Man in the Middle attack_. 

## Securing the connection to Windows AMIs
When you configure a template for a *Windows* instance (`Type AMI` field), you can use HTTPS and disallow 
self-signed certificates. This guarantees the instance you're connecting to is the expected one and a 
[man-in-the-middle attack](https://en.wikipedia.org/wiki/Man-in-the-middle_attack) cannot be performed.

### AMI Set Up
Before securely connecting to the instance, you need to 1) configure the AMI, 2)install the 
certificate, 3) configure WinRM properly and 4) set the firewall rules to allow the connection. You can find some 
guidance at the `AMI Type` field help, under the template configuration on your Jenkins instance.

Tips:
* When the `Allow Self Signed Certificate` field is checked, the plugin checks the CA which issued the 
certificate and verifies the host it is connecting to is present on the certificate. If the field is not checked, both checks are skipped.
* The EC2 plugin connects to the instance using either an IP address. It does not use the DNS name. You must configure WinRM with a certificate which includes 
the **IP** of the instance. Something like: 
```
#3: ObjectId: 2.5.29.17 Criticality=false
SubjectAlternativeName [
  DNSName: myhostname.com
  IPAddress: 111.222.333.444  <--------------
]
```
