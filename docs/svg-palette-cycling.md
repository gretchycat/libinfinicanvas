# Infini SVG palette cycling

Canvas recognizes the namespace `https://infinicanvas.org/svg` independently of the chosen XML prefix. This implements the metadata supplied with Infinimedia's `infini.svg` and its SVG-to-IFF converter directly, retaining the original palette, including palettes larger than 256 colors.

```xml
<svg xmlns="http://www.w3.org/2000/svg"
     xmlns:i="https://infinicanvas.org/svg" width="20" height="10">
  <metadata>
    <i:palette id="colors" size="3">
      <i:color index="0" value="#f00"/>
      <i:color index="1" value="#0f0"/>
      <i:color index="2" value="#00f"/>
    </i:palette>
    <i:cycle palette="colors" start="0" end="2" rate="2"
             unit="steps-per-second" direction="forward" repeat="indefinite"/>
  </metadata>
  <rect width="20" height="10" i:palette-index="0"/>
</svg>
```

A single indexed palette defines every index from zero through size minus one. Size can be inferred when absent. Colors use #RGB or #RRGGBB. Up to 256 disjoint inclusive ranges select positive finite rates, forward/reverse direction and indefinite repetition. At time zero colors use their declared indices; forward cycling moves each color towards increasing indices, wrapping inside its range. Untagged paints and indices outside cycling ranges remain fixed.

`palette-index` binds the element's non-none fill/stroke, or the default fill when no paint is declared. On gradient stops it binds stop-color. The decoder rasterizes flat indexed paints once at the requested size. A small fixed number of setup renders separates static artwork, animated coverage and palette IDs. Subsequent frames rotate the original palette through cached pixel bindings; there is no SVG traversal, CSS construction or bitmap allocation per frame. Unrelated equal-RGB pixels remain fixed, and duplicate palette colors keep distinct IDs.

The initial frame matches the vector render. Alpha, fixed gradients, clipping and transforms are captured in the raster. Pixels blending several indexed paints are represented by a nearest palette index plus their original static/antialias contribution, so mixed edges/overlaps can have small color approximation errors as colors rotate. This does not restrict the palette to 256 colors. Tagged animated gradient stops retain the vector renderer to preserve their continuous interpolation; that fallback can still be expensive for complex scenes. Source SVG bytes are never rewritten.

`FramePacedBitmapAnimation` optionally advertises a frame interval. Raster SVG uses 16–33 ms according to its fastest cycle. Hosts should include worker and publication time in that interval, rather than adding a fixed delay after every completed frame. Wall-clock timing still advances if the OS stalls; a display cannot present every step of arbitrarily fast cycles.

Unknown palette references, incomplete/duplicate indices, overlapping ranges, unsupported units/repeats/directions and external entity declarations reject animation metadata. The viewer can retain an ordinary still preview with a warning. Theme imports reject invalid layer manifests and use their existing visual fallback if an image cannot decode. XML is bounded to 8 MiB expanded, palettes to 1,000,000 entries, and rendered animation frames to 4 megapixels. AndroidSVG's existing SVG subset still applies; this does not add script or general SVG/SMIL execution.

`ThumbnailDecoder.decodeViewerFrame` and `CanvasStreams.decodeViewer` return the renderer in BitmapFrame.animation. `CanvasStreams.decodeBytes(bytes, name, targetPixels)` supplies the same discovered codecs and animation handling for in-memory theme assets; its encoded limit is 8 MiB and SVG retains intrinsic document canvas dimensions. Caller owns bitmap/frame presentation and must stop rendering when hidden. Thumbnail requests remain still previews.

`CanvasStreams.decodeBytes(bytes, name, targetPixels, false)` returns only the initial still; SVG skips raster-animation setup. Other formats retain their existing first-frame decoder path before playback state is discarded. Palette extraction uses this route. Existing decodeBytes overloads retain their signatures. Raster setup costs more than a single vector frame, but the parsed SVG scene and setup probes are released afterward; cached animation retains only bounded pixel/index arrays and its original palette. No sequence of full-frame images is stored.
