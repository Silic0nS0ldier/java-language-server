set -e

# Copies `node_modules` out from Bazel
echo "Building node_modules"
bazel build \
    //:node_modules \
    //extension:node_modules \
    //build_defs/rollup_bundle:node_modules \
    //build_defs/vsce_package:node_modules

echo "Cleaning old node_modules"
rm -rf ./node_modules
rm -rf ./extension/node_modules
rm -rf ./build_defs/rollup_bundle/node_modules
rm -rf ./build_defs/vsce_package/node_modules

echo "Copying new node_modules"
cp -r --no-preserve=mode ./.bazel/bin/node_modules ./
cp -r --no-preserve=mode ./.bazel/bin/extension/node_modules ./extension
cp -r --no-preserve=mode ./.bazel/bin/build_defs/rollup_bundle/node_modules ./build_defs/rollup_bundle
cp -r --no-preserve=mode ./.bazel/bin/build_defs/vsce_package/node_modules ./build_defs/vsce_package

# Generate `rust-project.json`
echo "Building rust-project.json"
bazel run @rules_rust//tools/rust_analyzer:gen_rust_project 
