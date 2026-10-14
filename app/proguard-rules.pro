# libalphaskiajni.so resolves alphaTab.alphaSkia.AlphaSkiaNative and its handle
# field by name from JNI, so the whole binding package must stay unobfuscated.
-keep class alphaTab.alphaSkia.** { *; }
