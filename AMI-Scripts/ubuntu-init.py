#!/usr/bin/python
import os
import httplib
import string

os.system("sudo apt-get update")
os.system("sudo apt-get install openjdk-7-jre")

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
os.system("java -jar slave.jar -jnlp " + jenkinsUrl + "computer/" + slaveName + "/slave-agent.jnlp")
