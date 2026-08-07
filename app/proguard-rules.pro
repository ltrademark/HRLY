# R8 rules for the release build (isMinifyEnabled + isShrinkResources).
#
# This file is short on purpose. Nothing in the app is reflection-driven: no Class.forName, no
# getIdentifier, no Serializable, no custom Parcelable, no enums. The only ::class.java uses are
# Intent(context, X::class.java) and getSystemService(X::class.java), both of which R8 follows.
#
# The four manifest components (MainActivity, ChimeService, BootReceiver, ChimeReceiver) and the
# custom view in dialog_crop.xml are kept by the rules AAPT2 generates from the merged manifest
# and the layouts, so they are deliberately not repeated here. ChimeReceiver's name in particular
# MUST survive: alarms scheduled by 1.72 and later name the class explicitly in their
# PendingIntent, and those alarms outlive an app update.

# Shrink and optimize HRLY's own code, but never rename it. App code is a rounding error in the
# dex next to appcompat/material/kotlin, so this gives up almost nothing in size, and it keeps
# every HRLY frame in a user's logcat readable. That matters most for F-Droid builds, which are
# built from source on their machines with a mapping file we never see.
-keepnames class com.ltrademark.hourly.** { *; }

# Line numbers in those traces, under the original file name rather than a renamed one.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
