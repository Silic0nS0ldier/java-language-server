#!/usr/bin/env bash
# shellcheck shell=bash

set -eax

echo "Running install..."

ARCH="$(uname --machine)"

# renovate: datasource=github-tags depName=bazelbuild/bazelisk
BAZELISK_VERSION="v1.29.0"
# renovate: datasource=github-tags depName=bazelbuild/buildtools
BUILDTOOLS_VERSION="v8.5.1"
# renovate: datasource=github-tags depName=withered-magic/starpls
STARPLS_VERSION="v0.1.22"
# renovate: datasource=npm depName=pnpm
PNPM_VERSION="11.17.0"
# renovate: datasource=github-tags depName=nodejs/node
NODE_VERSION="v26.5.0"
# renovate: datasource=github-releases depName=dprint/dprint
DPRINT_VERSION="0.55.2"
# renovate: datasource=github-releases depName=google/google-java-format
GOOGLE_JAVA_FORMAT_VERSION="v1.36.1"

if [[ $ARCH == "arm64" ]] || [[ $ARCH == "aarch64" ]]; then
    curl "https://github.com/bazelbuild/bazelisk/releases/download/${BAZELISK_VERSION}/bazelisk-linux-arm64" -Lo /usr/local/bin/bazel
    curl "https://github.com/bazelbuild/buildtools/releases/download/${BUILDTOOLS_VERSION}/buildifier-linux-arm64" -Lo /usr/local/bin/buildifier
    curl "https://github.com/bazelbuild/buildtools/releases/download/${BUILDTOOLS_VERSION}/buildozer-linux-arm64" -Lo /usr/local/bin/buildozer
    curl "https://github.com/bazelbuild/buildtools/releases/download/${BUILDTOOLS_VERSION}/unused_deps-linux-arm64" -Lo /usr/local/bin/unused_deps
    curl "https://github.com/withered-magic/starpls/releases/download/${STARPLS_VERSION}/starpls-linux-aarch64" -Lo /usr/local/bin/starpls
    curl "https://github.com/pnpm/pnpm/releases/download/v${PNPM_VERSION}/pnpm-linux-arm64.tar.gz" -Lo /tmp/pnpm.tar.gz
    curl "https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-arm64.tar.xz" -Lo /tmp/node.tar.xz
    curl "https://github.com/dprint/dprint/releases/download/${DPRINT_VERSION}/dprint-aarch64-unknown-linux-gnu.zip" -Lo /tmp/dprint.zip
    curl "https://github.com/google/google-java-format/releases/download/${GOOGLE_JAVA_FORMAT_VERSION}/google-java-format_linux-arm64" -Lo /usr/local/bin/google-java-format
elif [[ $ARCH == "x86_64" ]]; then
    curl "https://github.com/bazelbuild/bazelisk/releases/download/${BAZELISK_VERSION}/bazelisk-linux-amd64" -Lo /usr/local/bin/bazel
    curl "https://github.com/bazelbuild/buildtools/releases/download/${BUILDTOOLS_VERSION}/buildifier-linux-amd64" -Lo /usr/local/bin/buildifier
    curl "https://github.com/bazelbuild/buildtools/releases/download/${BUILDTOOLS_VERSION}/buildozer-linux-amd64" -Lo /usr/local/bin/buildozer
    curl "https://github.com/bazelbuild/buildtools/releases/download/${BUILDTOOLS_VERSION}/unused_deps-linux-amd64" -Lo /usr/local/bin/unused_deps
    curl "https://github.com/withered-magic/starpls/releases/download/${STARPLS_VERSION}/starpls-linux-amd64" -Lo /usr/local/bin/starpls
    curl "https://github.com/pnpm/pnpm/releases/download/v${PNPM_VERSION}/pnpm-linux-x64.tar.gz" -Lo /tmp/pnpm.tar.gz
    curl "https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-x64.tar.xz" -Lo /tmp/node.tar.xz
    curl "https://github.com/dprint/dprint/releases/download/${DPRINT_VERSION}/dprint-x86_64-unknown-linux-gnu.zip" -Lo /tmp/dprint.zip
    curl "https://github.com/google/google-java-format/releases/download/${GOOGLE_JAVA_FORMAT_VERSION}/google-java-format_linux-x86-64" -Lo /usr/local/bin/google-java-format
else
    echo "Unknown arch $ARCH"
    exit 1
fi

# pnpm installation
mkdir -p /usr/local/pnpm
tar -xzf /tmp/pnpm.tar.gz -C /usr/local/pnpm
rm /tmp/pnpm.tar.gz
ln -s /usr/local/pnpm/pnpm /usr/local/bin/pnpm

# node installation
mkdir -p /usr/local/node
tar -xf /tmp/node.tar.xz -C /usr/local/node --strip-components=1
rm /tmp/node.tar.xz
ln -s /usr/local/node/bin/node /usr/local/bin/node

# dprint installation
unzip -o /tmp/dprint.zip -d /usr/local/bin
rm /tmp/dprint.zip

chmod +x \
    /usr/local/bin/bazel \
    /usr/local/bin/buildifier \
    /usr/local/bin/buildozer \
    /usr/local/bin/unused_deps \
    /usr/local/bin/starpls \
    /usr/local/bin/pnpm \
    /usr/local/bin/node \
    /usr/local/bin/dprint \
    /usr/local/bin/google-java-format
