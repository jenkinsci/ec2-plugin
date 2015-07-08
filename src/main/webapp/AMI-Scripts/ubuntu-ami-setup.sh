#!/bin/bash

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <IP:Port of Jenkins Server>" >&2
  exit 1
fi

echo "Updating package list"
sudo apt-get update
echo "Installing dependencies"
sudo apt-get install wget python -y

echo "Downloading boot script"
sudo wget http://$1/plugin/ec2/AMI-Scripts/ubuntu-init.py -O /usr/bin/userdata
sudo chmod +x /usr/bin/userdata

echo "Adding boot script to run after boot is complete"
sudo sed -i '/^[^#]/ s/exit 0/python \/usr\/bin\/userdata\n&/' /etc/rc.local
