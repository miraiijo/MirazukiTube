# miryo tube: R8 shrinks and optimizes Compose (and androidx.tv) only.
# Every other class is kept whole, with its names: SmartTube's engine and its libraries use
# reflection (Retrofit, Gson, JsonPath, Rhino), as do the forked leanback / fragment modules.
-dontobfuscate
-keepattributes *
-keep class !androidx.compose.**,!androidx.tv.**,** { *; }
-dontwarn **
