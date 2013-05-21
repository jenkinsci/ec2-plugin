#!/usr/bin/python
import os
import httplib
import string

# To install run:
# sudo wget http://$JENKINS_URL/plugin/ec2/AMI-Scripts/ubuntu-init.py -O /usr/bin/userdata
# sudo chmod +x /etc/init.d/userdata
# add the following line to /etc/rc.local "python /usr/bin/userdata"

# If java is installed it will be zero
# If java is not installed it will be non-zero
hasJava = os.system("java -version")

if hasJava != 0:
    os.system("sudo apt-get update")
    os.system("sudo apt-get install openjdk-7-jre -y")

conn = httplib.HTTPConnection("169.254.169.254")
conn.request("GET", "/latest/user-data")
response = conn.getresponse()
userdata = response.read()

args = string.split(userdata, "&")
jenkinsUrl = ""
slaveName = ""

for arg in args:
    if arg.split("=")[0] == "JENKINS_URL":
        jenkinsUrl = arg.split("=")[1]
    if arg.split("=")[0] == "SLAVE_NAME":
        slaveName = arg.split("=")[1]
		
os.system("wget " + jenkinsUrl + "jnlpJars/slave.jar -O slave.jar")
os.system("java -jar slave.jar -jnlpUrl " + jenkinsUrl + "computer/" + slaveName + "/slave-agent.jnlp")

