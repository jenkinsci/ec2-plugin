#!/bin/bash
JENKINS_URL="http://localhost:8080/"
jenkcli="sudo jenkins-cli -s $JENKINS_URL"

mvn -DskipTests

echo "Installing plugin built at `stat -c %y target/ec2.hpi`"

$jenkcli install-plugin target/ec2.hpi
$jenkcli safe-restart

echo "Finished installing at `date`"

