# Source-only export

Original development project and its Git metadata/index were preserved. This tree has no original commit ancestry. It contains selected current source, tests, build files and permitted resources only.

Excluded: original .git; MP3/audio11; CLAP binary/NPY/model manifest and500-song metadata/Drive IDs; Figma SVG; uncertain raster art/album art; raw JSONL/location/photos/profiles; API keys/maps.local.properties/local.properties; source reports/exports/cache/APK/IDE files. No external SDK AAR/JAR was copied; only the verified Gradle Wrapper JAR is included.

Copy-only modifications: empty hardcoded R.raw catalog; separate applicationId; blank legacy analysis endpoint; debug entries unexported; Compose geometric replacements for three SVG helpers; own transport vector XML; optional private-model JVM test assumption and reproducible test working directory. Original implementation files were not changed.

Assets available at publish time are listed in ASSETS.md. The private copy receipt and security/history findings are held outside this repository, not uploaded.
