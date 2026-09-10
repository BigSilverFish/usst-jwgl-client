# Keep data model classes for Gson reflection
-keep class cn.edu.usst.jwgl.data.model.** { *; }
-keepclassmembers class cn.edu.usst.jwgl.data.model.** { *; }

# Keep Gson TypeToken
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Jsoup
-keep class org.jsoup.** { *; }
-dontwarn org.jspecify.**

# Keep Android Parcelable and Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }

# BlurView
-keep class eightbitlab.com.blurview.** { *; }
-dontwarn eightbitlab.com.blurview.**
