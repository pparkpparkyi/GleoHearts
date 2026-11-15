# Public asset manifest

Included:
- app/src/main/res/font/asta_sans.ttf — unchanged Asta Sans, SIL OFL1.1; full notice bundled.
- app/src/main/assets/licenses/asta_sans_OFL.txt — font notice.
- app/src/main/res/drawable/car_v10_play.xml, car_v10_pause.xml, car_v10_next.xml, car_v10_previous.xml — submission-created basic geometry.
- Existing project-owned XML launcher foreground/background and hearts_map_background.xml — geometric note/map-placeholder UI, not an actual geographical map.
- Adaptive launcher XML under mipmap-anydpi — links the XML drawable resources.
- app/src/debug/assets/schema.json — source-level debug Gleo action contract, not a claim of official voice runtime access.

Excluded: all figma-v12/figma-v13/figma-review-final SVG; original car_v10 PNG; raster launcher WebP; audio11/album covers; CLAP prototypes/embeddings/500-song registry/Drive identifiers. No Figma screenshot is bundled as a runtime asset. Public screenshots, if present, are captured from this source-only build and synthetic fixtures, never from personal records.

Fallback drawing code: app/src/main/java/com/example/gleo_type/ui/app/SubmissionVector.kt. It draws simple circles/lines and radial gradients; it does not trace excluded SVG paths. Fidelity is deliberately marked PARTIAL.
