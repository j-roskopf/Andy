# Andy Android Helper

This is a helper Java utility that runs on the Android device via `app_process`.
It allows Andy to:
1. Retrieve exact human-readable application labels (names).
2. Extract high-quality app icons as PNG bytes.

## How to Rebuild

To rebuild and copy to the resources folder, run the following commands:

```bash
# 1. Compile the Java source with the Android SDK framework class library
javac -bootclasspath ~/Library/Android/sdk/platforms/android-36/android.jar -source 1.8 -target 1.8 -d . Helper.java

# 2. Convert the compiled class to a DEX jar using d8
~/Library/Android/sdk/build-tools/37.0.0/d8 --output ../src/desktopMain/resources/andy-helper.jar app/andy/helper/Helper.class

# 3. Clean up compiled classes
rm -rf app
```
