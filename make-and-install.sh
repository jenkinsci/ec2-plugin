JENKINS_URL="http://localhost:8080/"
jenkcli="sudo java -jar ../jenkins-cli.jar -s $JENKINS_URL"

pushd ec2-plugin/

mvn -DskipTests

$jenkcli install-plugin target/ec2.hpi
$jenkcli safe-restart

popd
date

