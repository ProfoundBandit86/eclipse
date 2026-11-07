# Eclipse R8 rules. Libraries that find their own classes by name (SMB, crypto, logging, JavaScript) are kept whole.
-keep class jcifs.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.slf4j.** { *; }
-keep class com.caverock.androidsvg.** { *; }

# Things the system creates from the manifest by name
-keep class com.eclipse.launcher.** extends android.app.Service { *; }
-keep class com.eclipse.launcher.** extends android.accessibilityservice.AccessibilityService { *; }
-keep class com.eclipse.launcher.** extends android.service.notification.NotificationListenerService { *; }
-keep class com.eclipse.launcher.** extends android.content.BroadcastReceiver { *; }

# Optional pieces these libraries mention but don't need on Android
-dontwarn javax.**
-dontwarn java.beans.**
-dontwarn java.lang.management.**
-dontwarn sun.**
-dontwarn org.apache.**
-dontwarn org.ietf.jgss.**
-dontwarn org.osgi.**
-dontwarn jcifs.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn com.google.errorprone.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
