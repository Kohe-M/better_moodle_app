# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class dev.rits.bettermoodle.** {
    *** Companion;
}
-keepclasseswithmembers class dev.rits.bettermoodle.** {
    kotlinx.serialization.KSerializer serializer(...);
}
