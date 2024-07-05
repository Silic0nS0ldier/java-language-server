set -e

# Copies `node_modules` out from Bazel
echo "Building node_modules"
bazel build \
    //:node_modules

echo "Cleaning old node_modules"
rm -rf ./node_modules

echo "Copying new node_modules"
cp -r --no-preserve=mode ./.bazel/bin/node_modules ./
