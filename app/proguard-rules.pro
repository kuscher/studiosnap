# Keep StudioSnap's own classes (activities, service, receiver are also referenced from the manifest).
-keep class io.github.kuscher.studiosnap.** { *; }
# Compose + AndroidX ship their own consumer rules; nothing else needed for this app.
