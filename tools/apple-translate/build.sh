#!/bin/sh
# Builds the universal translationshub-apple helper into the plugin's resources. Needs Xcode with the macOS 26 SDK.
set -e
cd "$(dirname "$0")"
OUT=../../idea-plugin/src/main/resources/bin/macos
mkdir -p "$OUT" build
for arch in arm64 x86_64; do
  xcrun swiftc -O -parse-as-library -target "$arch-apple-macos26.0" main.swift -o "build/translationshub-apple-$arch"
done
lipo -create build/translationshub-apple-arm64 build/translationshub-apple-x86_64 -output "$OUT/translationshub-apple"
codesign --force --sign - "$OUT/translationshub-apple"
echo "Built $OUT/translationshub-apple"
