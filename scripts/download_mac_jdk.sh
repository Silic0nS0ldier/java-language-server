#!/usr/bin/env bash
# Download a copy of mac JDK in jdks/mac

set -e

# Download mac jdk
mkdir -p jdks/mac
cd jdks/mac
curl https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.3%2B9/OpenJDK21U-jdk_x64_mac_hotspot_21.0.3_9.tar.gz -Lo mac.tar.gz
gunzip -c mac.tar.gz | tar xopf -
rm mac.tar.gz
mv jdk-21.0.3+9 jdk-21
cd ../..