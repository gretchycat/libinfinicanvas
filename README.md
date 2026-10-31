# libinfinicanvas

Structural extraction from gretchycat/infinifolder at 9233495.
Implementation ownership and validation are recorded in SOURCE_PROVENANCE.md.

Build using JDK 17+ and Gradle 9.7.1. Android adapters need SDK 36/min API 26.
Run `gradle :canvas-android:testDebugUnitTest` and the relevant assemble task.

Source dependencies: libinfinistream. Clone them beside this repository (or set -PinfiniLibrariesDir). InfiniFolder pins the complete tested set as submodules; standalone builds use these same sources, not unpublished Maven binaries. Android codec/protocol backends remain in explicit Android modules.

Original-code licensing is pending owner selection; see LICENSE.md. No original implementation is rewritten or relicensed by this move.

## Current boundary

Canvas owns ThumbnailDecoder, Android bitmap/vector/platform adapters and the existing custom raster/image decoders. URI/name-based icon policy (ImageFormats, CustomIconStore, DirectoryIconResolver), themes and viewer UI stay in the host. The library depends on Stream plus the existing AndroidSVG, JPEG XL and JPEG2000 adapters; it does not depend on Store, Vault or InfiniFolder. CanvasStreams accepts common source resources through the existing bounded 32 MiB streaming fallback; direct Android source decoding still supports sampled large images. BitmapFrame publishes actual dimensions, pixel format and color space without extra pixel copying; host bitmap ownership remains explicit. Synthetic fixtures and the original three codec regression tests follow the source. Existing Android/native codec AARs need F-Droid review.

## Full image viewer composition

`CanvasStreams.decodeViewer(resolver, resource, uri, targetPixels)` composes common source factories with the existing full-viewer decoder. The original URI retains native decoding when supported; source-based paths borrow independently opened streams. Bounds/sampling fallbacks and codec selection remain in Canvas. The returned borrowed `BitmapFrame` carries actual pixel dimensions/config/color space plus input provenance and warnings; the host owns bitmap lifetime. The consuming image viewer now uses this facade and retains its frame metadata. Its inspector derives current frame attributes after edits rather than reusing old dimensions.

The standalone build and ten JVM cases pass, including three checks that native URI hints must match the original untransformed resource. The containing app passes its expanded regression gate; actual image-viewer rendering/interaction remains handset acceptance.
