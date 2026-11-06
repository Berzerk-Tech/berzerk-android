# usdk da Urovo é compileOnly dentro do aar; o ROM do aparelho fornece em runtime.
-dontwarn android.content.pm.IPackageDeleteObserver
-dontwarn android.content.pm.IPackageInstallObserver
-dontwarn android.os.IKeyEventCallback
-dontwarn android.device.**
-dontwarn no.nordicsemi.android.dfu.**
-keep class com.ubx.usdk.** { *; }
# SDK RFID da Zebra: reflexão interna entre os módulos API3_*.
-keep class com.zebra.rfid.** { *; }
-keep class com.zebra.rfidhostlib.** { *; }
-keep class com.zebra.rfidseriallib.** { *; }
-dontwarn com.zebra.**
