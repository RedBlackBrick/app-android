# Retrofit — garder toutes les interfaces et @SerializedName / @Json
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit

# Moshi — garder les data classes annotées @JsonClass
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# Room — garder les entités et DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Glance widgets — garder les GlanceAppWidget et GlanceAppWidgetReceiver
-keep class * extends androidx.glance.appwidget.GlanceAppWidget
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver

# WireGuard — garder les classes JNI et tunnel
-keep class com.wireguard.** { *; }

# RootBeer — garder les détections natives
-keep class com.scottyab.rootbeer.** { *; }

# Kotlin — garder les métadonnées pour la réflexion
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

# Timber — supprimer les logs debug en release
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
