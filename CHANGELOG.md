## Changelog

### DEPRECATED
The changelog for new versions is at https://github.com/jenkinsci/ec2-plugin/releases

### Version 1.46 (September 25, 2019)
- Configure release drafter and Dependabot
- JENKINS-25106 - Support for minimum number of instances
- JENKINS-58578 - Force private IP also for public subnets
- JENKINS-59459 - Restore backward compatibility for connection strategy

### Version 1.45 (August 9, 2019)
#### Breaking change !!  - A new permission is needed in the iam role (see IAM Setup Section), if you want to launch Windows Agents and use a Generated Admin Password.
Role permission has to be extended with:
```json
"Effect": "Allow",
"Action": [
"ec2:GetPasswordData",
],
"Resource": "*"
```
- JENKINS-53952 - Wait for spot instances to get an instance id
- JENKINS-57215 - Fix stale uptime in EC2RetentionStrategy
- JENKINS-53322 - Cross-version dom4j compatibility tweaks
- Fix reconfiguration - Make sure templateDescription is preserved when reconfiguring a node
- Fix - Only check credentials once a minute
- Fix - Close stdin streams once shell is deleted
- Fix - workdir on windows
- Add - support for retrieving the windows password admin automatically
- Add - t3a instance support
- Update dependecies
- Clean up code

### Version 1.44 (June 24, 2019)
- JENKINS-56907 - Query AMI for platform before checking spot price
- JENKINS-57161 - Avoid queue to be blocked untill excessWorkload is 0
- JENKINS-33443 - Wait to touch ~/.hudson-run-init
- JENKINS-57562 - Fix NPE
- JENKINS-46294 - Allow block reservations for spot instances
- JENKINS-53858 - Fix implementation of double-check-locking
- JENKINS-55203 - Do not log private key material

### Version 1.43 (May 9, 2019)
- JENKINS-8618 - Maximum builds on agent
- JENKINS-54329 - User able to choose agent connection strategy
#### WARNING: This might imply some changes in the address used to connect to the EC2 agent.
- JENKINS-51526 - Clone AMI's Root Block device
- JENKINS-54536 - Allow all T3 types
- JENKINS-56443 - Don't provision nodes if Jenkins is shutting down

### Version 1.42.1 (July 3, 2019)
- JENKINS-55203 - Do not log private key material (security advisory)

### Version 1.41.1 (July 3, 2019)
- JENKINS-55203 - Do not log private key material (security advisory)

### Version 1.44.1 (June 27, 2019)
- JENKINS-58024 - Fix 0 max uses from older versions
- JENKINS-58163 - Add fallback when remoteFS is blank

### Version 1.44 (June 24, 2019)
- JENKINS-55203 - Do not log private key material (security advisory)
- Lock refactoring , reduce lock Hotness
- Discard stale connections
- Fix JCasC compatibility
- Fix transient clock object
- Add SMB2 Support, for copying class
- Code clean up
- Fix windows tmpdir and quote appropriate arguments
- Add remoting workdir
- Add M5a instance support and fix M4
- Reduced call to  internalCheck in EC2RetentionStrategy
- Rename agent to remoting or agent
- JENKINS-56907 - Query AMI for platform before checking spot price

### Version 1.43 (May 9, 2019)
- Use secretTextarea for private keys
- Use a proper display name instead of the instance ID
- Fallback to on-demand when there is no spot instance capacity
- Allow launching spot instances without a bid price
- Allow alternate ec2 endpoint for API region calls
- Security FIX
- JENKINS-8618 - Support maximum builds on agent
- JENKINS-54329 - Allow user to choose agent connection strategy
- JENKINS-57357 - Fix migration config from old configuration
- JENKINS-56443 - Fix Don't provision a node if Jenkins is quieting down or terminating
- JENKINS-54536 - Allow all T3 types
- JENKINS-51526 - Clone the AMI's Root Block Device instead of making a new one from scratch

### Version 1.42 (Jan 5th , 2019)
- Added A1 instance type
- Expose deleteRootOnTermination to set it programmatically
- JENKINS-54041 - Fix Instance CAP calculation to exclude stopped instances
- JENKINS-54266 - Fix Do not tag instances if no serverUrl
- JENKINS-54315 - Fix support  properly "eventual consistency of AWS API's"
- JENKINS-53858 - Fix deadlock
- JENKINS-48548 - Add T2 Unlimited Support
- JENKINS-54271 - Fix Missing display name for administrative monitor



### Version 1.41 (Oct 24th, 2018)
#### Breaking change !!
Role permission has to be extended with the :
```json
"Effect": "Allow",
"Action": [
"iam:ListInstanceProfilesForRole",
"iam:PassRole"
],
"Resource": "*"
```
The existing nodes has to be terminated due a new tag schema

- JENKINS-54071 - Fix plugin not spooling up stopped nodes
- JENKINS-49814 - Fix Jenkins trying to stop already stopped agent
- JENKINS-35708 - Allow users to supply multiple subnets for launching EC2 instances. This is useful for resources that may need to spread across availability zones such as GPUs.
- JENKINS-52828 - Add agent suffix command.
- PR-310 - Tag agent instances to their jenkins master (breaking change)
- PR-309 - Add M5 instance types

### Version 1.40.1 (Oct 2nd, 2018)
#### This version has problem with the option stop instance
- PR-306 - FIX  JENKINS-53879  - EC2 workers terminated before connection can be established, in some envs

### Version 1.40 (Oct 1st, 2018)
- PR-250 - Add support for c5 and m5 instance types.
- JENKINS-25832 - Launch multiple agents in parallel for jobs with same node label
- JENKINS-48979 - Race condition when setting tags
- PR-259 - Make fetch time configurable via system property
- PR-263 - Fix intermittent "Pipe closed" exception when communicating with WinRM protocol agent
- PR-265 - Mark dependencies used only in tests as test scope
- JENKINS-50105 - EC2 Step provisioning incorrectly specifies a label
- JENKINS-38311 - Disconnected dynamic ec2 agents reconnected after jenkins restart
- PR-272 - Improving debug messaging for WinConnector.
- PR-275 - Terminate spot instances properly
- PR-276 - Start agent in cygwin friendly way
- PR-280 - Add support for c5d and m5d instance types.
- PR-289 - Upgrade parent pom.
- PR-290 - Convert global findbugs skip to specific per class
- PR-292 - Add incrementalify support.
- JENKINS-52319 - userData field triggers an NPE when not explicitly initialized to "" (empty string)
- JENKINS-52334 - Mode should default to NORMAL when absent
- PR-294 - Introduce a ProvisionerStrategy to provision a node without delay
- JENKINS-53285 - Use latest node-iterator-api.
- PR-299 - Add Gitter chat and badge.
- PR-300 - Add option for detailed monitoring of instances.
- PR-301 - Fix more findbugs issues.

### Version 1.39 (Mar 11, 2018)
- JENKINS-47985 EC2 Plugin doesn't store AMITypeData in config.xml
- JENKINS-46869 Can not register an EC2 instance as a node agent
- JENKINS-47130 EC2 plugin 1.37 fails to provision previously defined agents

### Version 1.38 (Dec 6, 2017)
- This is a bad scary version due to: JENKINS-47130 (see above), please use 1.39 or higher instead
- Fix security issue
- JENKINS-47593 Make the plugin work on Jenkins 2.86 and newer

### Version 1.37 (Sept 25, 2017)
#### This is a bad scary version due to: JENKINS-47130 (see above), please use 1.39 or higher instead
#### Warning:  This version introduces a bug ( JENKINS-47985 - EC2 Plugin doesn't store AMITypeData in config.xml CLOSED ) which drops stored passwords from Windows AMIs.  The fix has been merged to mainline and should hopefully be available when version 1.39 gets released.

### Version 1.36 (Oct 2, 2016)
- JENKINS-38481 AWS method change causes too many agents to be launched

### Version 1.33 (May 9, 2016)
- JENKINS-34667 Provision attempt is made when possible agents count is zero (backed out)

### Version 1.32 (May 8, 2016)
#### Warning: Please use 1.33 instead, it has a critical fix.
#### Welcome Johnny Shields as co-maintainer!
- JENKINS-26371 Ensure instance initiated shutdown behavior is consistent with stopOnTerminate flag
- JENKINS-27529 Poll for spot instances instead of JNLP launcher
- JENKINS-32588 Unable to launch linux agents using ec2 plugin using Eucalyptus
- JENKINS-32584 ec2-plugin counts every instance (not just Jenkins agent instances) when counting agents; incorrectly hits agent cap
- JENKINS-32690 Make manually provision really work
- JENKINS-32915 Spot instance launched one after another until capacity reached for single task in queue
- JENKINS-33945 can't provision new nodes when a matching node is marked offline
- JENKINS-34667 Provision attempt is made when possible agents count is zero
- EC2UnixLauncher can survive Connection IOExceptions
- Bootstrap retries with fresh connection
- Many pmd/squid fixes
- Remove AWS fault instance
- Increase minimal supported version to 1.625.1 (to enable JDK7)
- Use AWS credentials to manage the IAM access key
- Applied logic to create block device mappings for both ondemand and spot agents
- With agents now being fully controlled by Jenkins master, there is no longer a need to be able to set "persistent" spot bids

### Version 1.31 (Jan 25, 2016)
- JENKINS-32584 ec2-plugin counts every instance (not just Jenkins agent instances) when counting agents; incorrectly hits agent cap

### Version 1.30 (Jan 23, 2016)
- Add config to prefer the public IP to private IP when ssh-ing into agent
- Added common method to compute tag value and also created constants for demand and spot
- JENKINS-27601 instance caps incorrectly calculated
- JENKINS-23787 EC2-plugin not spooling up stopped nodes
- Depend on the aws-java-sdk plugin to limit AWS SDK duplication
- Upgrade AWS SDK to 1.10.26
- Terminate instance even if ec2 node deletion failed
- JENKINS-27260 SPNEGO for Windows in the EC2 Plugin
- JENKINS-26493 Use new EC2 API endpoint hostnames
- JCIFS first tries to resolve a dfs path would timeout causing a long startup delay
- JENKINS-28754 Jenkins EC2 Plugin should show timestamp in agent logs
- JENKINS-30284 EC2 plugin too aggressive in timing in contacting new AWS instance over SSH
- Use AWS4SignerType instead of QueryStringSignerType
- Add minimum timeout for windows launching
- Better exception handling in uptime check
- JENKINS-29851 Global instance cap not calculated for spot instances
- JENKINS-32439 JENKINS-32439 Incorrect agent template (AMI) found when launching agent
- Improve logging to be less verbose

### Version 1.29 (Aug 2, 2015)
- Modify sed command for Ubuntu to ignore comments
- Add option to launch EBS optimized instances
- Fix to not hide windows connection errors.
- Fix confusing log message in windows launcher
- Update AWS SDK to 1.10.0 (new instance types)
- JENKINS-28268 - Allow ability to programmatically update AMI using Groovy

### Version 1.28 (Jun 7, 2015)
- JENKINS-26854 - Fixing 'RequestExpired'
- Fixing instance profile credentials with 'Check AMI' and 'Check Current Spot Price' buttons
- Masking BouncyCastle for when the system version is incompatible with the required version
- Fix java download/install
- Check more places where passing a null instanceID can do a search of random spot instances
- WiP - Launch agent agent via ssh client process
- JENKINS-24359 - Overcoming limit of one cloud per region.
- JENKINS-27260 - SPNEGO for Windows in EC2 Plugin

### Version 1.27 (Mar 30, 2015)
- JENKINS-26797 - fixed zombie instances
- JENKINS-26414 - added support for new C4 instance types

### Version 1.26 (Jan 27, 2015)
- Problem starting with empty tmpdir (JENKINS-26531)

### Version 1.25 (Jan 20, 2015)
- Make temporary directories configurable (JENKINS-26232)
- Add immediate check for useInstanceProfileForCredentials option
- Include new parameter in jelly's with-clauses, respect it in doFillRegions
- Fixing zombie workers
- Add option to obtain EC2 credentials from instance meta data service
- Retry updating remote tags also for on-demand instances
- Support cloud-formation compatible user-data in spot-instance agents
- Handle AssociatePublicIp in spot instance agents
- Consider only agents for the requested label when decrementing excess Workload by number of pending spot instance agents
- Fix the stopping/pending message when launching a agent
- Fix bug that caused infinite loop of NullPointerExceptions during provisioning of windows instances.
- Fix connection to Windows agents outside us-east-1. (JENKINS-19943)
- Give the user a readable error message when testing the connection instead of stack trace if their credentials are incorrect. (JENKINS-24676)
- EC2 plugin incorrectly reports current instance count (JENKINS-19845)
- Fix EC2SpotAgents from fetching tags from a completely different instance

### Version 1.24 (Aug 5, 2014)
 
### Version 1.23 (Jun 30, 2014)
- Fixed critical problems in 1.22.

### Version 1.22 (Jun 27, 2014)
#### This was a bad release, not recommended. Use 1.23 instead.

### Version 1.21 (Feb 28, 2014)
 
### Version 1.20 (Nov 8, 2013)
- Use the Node Iterator API Plugin to track provisioned nodes.

### Version 1.19 (Oct 8, 2013)
- Spot instance support (pull request ec2-plugin/43)
- Fixed a problem with multiple EC2 Clouds (JENKINS-15081)
- Amazon IAM support (JENKINS-17086)
- Fixed Eucalyptus connectivity support (JENKINS-18854)
- Fix: spot instance feature cannot be used within VPC (JENKINS-19301)
- Remove agent from Jenkins even when instance termination fails (JENKINS-19500)

### Version 1.18 (April 9, 2013)
- Add m3.xlarge and m3.2xlarge instance types
- Failure starting agent nodes (JENKINS-15319)
- Tags feature is broken (JENKINS-15239)
- EC2 Nodes which share an AMI ID get the wrong labels (JENKINS-7690)
- Sometimes starts the wrong instance (JENKINS-15158)
- Stopped (as opposed to terminated) agents are counted against the active instance count for the purpose of launching; can prevent launching of instances (JENKINS-7883)
- Upgrade aws-java-sdk dependency to 1.3.30
- Explicitly add MIT license to all plugin code
- Fallback a manual or timeout-based terminate to stop if terminate fails (to avoid charges)
- Give Jenkins nodes useful names (JENKINS-15078)
- Keep track of instances being provisioned; use this count when determining total/AMI instance caps (JENKINS-6691)
- Bring back remoteFS in the agent configuration page
- Let user configure node.mode for EC2 agents

### Version 1.17 (September 12, 2012)
- Resume stopped EC2 instances (JENKINS-14884)
- Added support for EC2 tags, VPC subnets/security groups
- Added support for public/private DNS
- EC2 documentation for VPC/security groups not clear (JENKINS-15149)

### Version 1.16 (May 26, 2012)
- EC2 Userdata not being Base64 encoded (JENKINS-13897)

### Version 1.15 (May 21, 2012)
- Stopped (as opposed to terminated) agents are counted against the active instance count for the purpose of launching; can prevent launching of instance (JENKINS-7883)
- Clarification and updating of help (JENKINS-12789)
- The init script was called each time instance was connected to (JENKINS-12771)
- EC2 agents fail to launch when using versions prior to 1.9 (JENKINS-7219)
- Force registration of converter (JENKINS-10118)
- Convert to Amazon EC2 libraries(JENKINS-12539)
- Allow non-root user name (JENKINS-5867)
- Allow specification of security group (JENKINS-8617)
- Add support for M1 Medium instance (JENKINS-13432)
- Allow instances to be stopped (instead of terminated (JENKINS-12772)
- Option to set zone as well as region for instance (JENKINS-8946)

### Version 1.14 (Feb 22, 2012)
- Fixed a typo in Tokyo region name, and added Oregon and Sao Paulo regions.

### Version 1.13 (Jul 29, 2011)
- Fixed NPE (JENKINS-10467)

### Version 1.12 (Jul 19, 2011)
- Label expressions are handled correctly (JENKINS-9773)
- Fixed a false-positive "check AMI" validation error (JENKINS-9415)

### Version 1.11 (Mar 15, 2011)
- Fixed a bug in the form validation (JENKINS-6063)
- Reuse the client for better resource usage (pull request).
- SSH port is now configurable
- Eucalyptus doesn't report the IP address right away, so we need to keep checking. (JENKINS-5851)
- Added new Tokyo region.

### Version 1.10 (Nov 7 2010)
- Added APAC region support.

### Version 1.9 (Aug 11 2010)
- Fixed bug in backwards-compatibility of persisted configuration (JENKINS-6782)

### Version 1.8 (Apr 09 2010)
- Fixed the fatal problem in configuration persistence (JENKINS-6113)

### Version 1.7 (Mar 17 2010)
- Supported different AWS regions (JENKINS-4796)

### Version 1.6 (Feb 15 2010)
- Support Eucalyptus (AKA Ubuntu Enterprise Cloud) and ubuntu AMI's.

### Version 1.5 (Nov 20 2009)
- Number of executors wasn't properly persisted (JENKINS-4906)

### Version 1.4 (Aug 5 2009)
- Jenkins does several retries on SSH logins to give sshd extra time to initialize itself (JENKINS-4119)

### Version 1.3 (July 27 2009)
 - User-data can be now specified to the launching instances (JENKINS-4115)
- \# of executors can be now configured (JENKINS-4116)

### Version 1.2 (Jun 18 2009)
- Fixed the dependency issue with recent Jenkins

### Version 1.1 (May 28 2009)
- Re-implemented the instance cap so that it uses AWS API for # of instances to prevent run-away EC2 instances.
- Improved the performance of instance launches
- Fixed a bug where a long init script may cause Jenkins to start additional instances.
- If the init script has run once, don't run it again when reconnecting.

### Version 1.0
- Initial release
