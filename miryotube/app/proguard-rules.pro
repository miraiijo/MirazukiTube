# miryo tube: R8 shrinks and optimizes the libraries that ship their own R8 rules or need no
# reflection: AndroidX (Compose and androidx.tv included), Kotlin, coroutines, Guava, RxJava,
# ExoPlayer, Glide, ZXing. Their unused classes go (bêta 26 kept every class: 24.5 MB of dex).
# Every other class is kept whole, with its names: SmartTube's engine and the libraries it reaches
# by reflection or JNI (Retrofit, Gson, JsonPath, Rhino, J2V8, Cronet, protobuf, kotlinx.serialization).
-dontobfuscate
# No LocalVariableTable: nothing reads local variable names, they only weigh on the dex.
-keepattributes Signature,*Annotation*,AnnotationDefault,InnerClasses,EnclosingMethod,Exceptions,MethodParameters,SourceFile,LineNumberTable
-keep class !androidx.**,!kotlin.**,!kotlinx.coroutines.**,!com.google.common.**,!io.reactivex.**,!com.google.android.exoplayer2.**,!com.bumptech.glide.**,!com.google.zxing.**,** { *; }

# ExoPlayer: the live DASH parser (player/upstream/LiveDashManifestParser) reads and writes its
# manifest fields by name (Helpers.getField/setField). Every ExoPlayer field stays, untouched.
-keepclassmembers class com.google.android.exoplayer2.** { <fields>; }
# SABR messages (protobuf-lite): their fields are read by name.
-keep class com.google.android.exoplayer2.source.sabr.protos.** { *; }
# Guava futures reach their fields through Unsafe / field updaters, by name.
-keepclassmembers class com.google.common.util.concurrent.AbstractFuture** { *; }

-dontwarn **
