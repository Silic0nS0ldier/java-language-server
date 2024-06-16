#!/usr/bin/env bash
# Download a copy of linux JDK in jdks/linux

set -e

# Download linux jdk
mkdir -p jdks/linux
cd jdks/linux
curl https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.3%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.3_9.tar.gz -Lo linux.tar.gz
gunzip -c linux.tar.gz | tar xopf -
rm linux.tar.gz
mv jdk-21.0.3+9 jdk-21
cd ../..