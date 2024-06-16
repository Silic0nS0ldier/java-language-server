#!/usr/bin/env bash
# Download a copy of windows JDK in jdks/windows

set -e

# Download windows jdk
mkdir -p jdks/windows
cd jdks/windows
curl https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.3%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.3_9.zip -Lo windows.zip
unzip windows.zip
rm windows.zip
mv jdk-21.0.3+9 jdk-21
cd ../..