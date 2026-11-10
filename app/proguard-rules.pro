# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class dev.pridgey.brig.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.pridgey.brig.**$$serializer { *; }

# Readable crash reports (Diagnostics → Last crash): keep Brig's class names and line numbers.
# Names only; unused code is still removed.
-keepattributes SourceFile,LineNumberTable
-keepnames class dev.pridgey.brig.** { *; }
