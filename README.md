# Android Uninstall Stock
**Full cycle** of removing stock packages on Android on generated list.

**Requires Root!**<br />
And activation "Debugging USB" on the device.

To run Java and need ADB from Android SDK.

### Format
```xml
<?xml version="1.0" encoding="UTF-8"?>
<AndroidUninstallStock>
<Normal>
<apk name="anyName" url="https://play.google.com/store/apps/details?hl=lang&amp;id=com.packagename">
	<description>Description</description>
	<include in="package" pattern="anyN" case-insensitive="true" />
	<include in="apk" pattern="^anyN.*" regexp="true" case-insensitive="true" />
	<!-- in = package - com.pack.name (default)
		apk - filename.apk
		path - /system/app/filename.apk
		path+package - /system/app/filename.apkcom.pack.name
		library - /system/lib/libname.so
		.odex .dex autoreplace with .apk
	-->
	<exclude in="apk" pattern="^anyN.*" regexp="true" case-insensitive="true" />
	<exclude global="true" in="apk" pattern="^globNa.*" regexp="true" case-insensitive="true" />
	<!-- exclude global - after all include+exclude in section -->
</apk>
</Normal>
<Google>
<!-- Official Android packages -->
<apk name="anyName" url="https://play.google.com/store/apps/details?hl=lang&amp;id=com.packagename">
</apk>
</Google>
</AndroidUninstallStock>
```
#### Code style
Tab = 4 space only<br />
Line = 120 symbols
