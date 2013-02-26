# This script will silently configure a live instance to be able to run as a Jenkins Spot Slave
# This needs to be run in powershell as Administrator
# Until we sign these scripts, you will need to run something like the following to allow execution
# "Set-ExecutionPolicy RemoteSigned"

$downloadLocation = $pwd
$installDir = "C:\Java"

# Download Java
$webclient = New-Object System.Net.WebClient
$url = "http://javadl.sun.com/webapps/download/AutoDL?BundleId=74788"
$file = "$downloadLocation\java.exe"
$webclient.DownloadFile($url,$file)

# Execute Java installer
[System.Diagnostics.Process]::Start("$file", "/s INSTALL_DIR=$installDir")
[environment]::SetEnvironmentVariable('JAVA_HOME', $installDir, 'machine')

# Download Start Up Powershell Script
$webclient = New-Object System.Net.WebClient
$url = "https://raw.github.com/bwall/ec2-plugin/master/AMI-Scripts/powrshell-init.ps1"
$file = "$downloadLocation\jenkins-spot-startup.ps1"
$webclient.DownloadFile($url,$file)

# Run jenkins-spot-startup.ps1 on boot
[System.Diagnostics.Process]::Start("schtasks.exe", "/create /SC ONSTART /TN JENKINSSPOT /TR 'powershell.exe -noprofile -executionpolicy Unrestricted -file $file'")
