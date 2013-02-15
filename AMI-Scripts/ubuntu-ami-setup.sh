#!/bin/bash

echo "Updating package list"
sudo apt-get update
echo "Installing dependencies"
sudo apt-get install openjdk-7-jre wget python -y

echo "Downloading boot script"
sudo wget https://raw.github.com/bwall/ec2-plugin/master/AMI-Scripts/ubuntu-init.py -O /usr/bin/userdata
sduo chmod +x /usr/bin/userdata

echo "Adding boot script to run after boot is complete"
sudo sed -i 's/exit 0/python \/usr\/bin\/userdata\n&/' /etc/rc.local
