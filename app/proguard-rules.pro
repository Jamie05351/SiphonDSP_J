# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontobfuscate

-keep class dev.doubledot.doki.** { *; }

-keep class me.timschneeberger.hiddenapi_impl.** { *; }

-keep,allowoptimization class app.siphondsp.interop.** { *; }
-keep,allowoptimization class app.siphondsp.fragment.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclasseswithmembernames class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepclasseswithmembernames class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Native code calls back into these by name (JamesDspWrapper.cpp, EelVmVariable.cpp). Covered by
# the interop keep above; repeated here so the dependency is explicit.
-keep class app.siphondsp.interop.structure.EelVmVariable { *; }

# Gson fills these by field name.
-keep class app.siphondsp.model.api.** { *; }
-keepattributes Signature, *Annotation*

# Passed through Bundles/Intents as java.io.Serializable.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Reflects on its own helper classes to lift hidden-API restrictions (MainApplication).
-keep class org.lsposed.hiddenapibypass.** { *; }

# Hidden system interfaces from :hidden-api-stubs (compileOnly), used by hidden-api-impl through
# Shizuku. They exist on the device but not on R8's classpath, so R8 must not treat them as missing.
-dontwarn android.permission.IPermissionManager
-dontwarn android.permission.IPermissionManager$**
-dontwarn com.android.internal.app.IAppOpsService
-dontwarn com.android.internal.app.IAppOpsService$**

# Keep line numbers so crash stack traces still point at real lines.
-keepattributes SourceFile,LineNumberTable
