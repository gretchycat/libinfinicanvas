# Infini SVG palette cycling

Canvas recognizes the namespace `https://infinicanvas.org/svg` independently of the chosen XML prefix. This implements the metadata supplied with Infinimedia's `infini.svg` and its SVG-to-IFF converter directly, retaining vector geometry and palettes larger than 256 colors.

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

`palette-index` binds the element's non-none fill/stroke, or the default fill when no paint is declared. On gradient stops it binds stop-color. Existing geometry, transforms, opacity, clipping and gradients retain AndroidSVG's behavior. Indexed paint uses runtime CSS; it does not recolor unrelated equal-RGB pixels. The renderer does not quantize the source or reparse vector paths for each animation frame.

Unknown palette references, incomplete/duplicate indices, overlapping ranges, unsupported units/repeats/directions and external entity declarations reject animation metadata. The viewer can retain an ordinary still preview with a warning. Theme imports reject invalid layer manifests and use their existing visual fallback if an image cannot decode. XML is bounded to 8 MiB expanded, palettes to 1,000,000 entries, and rendered animation frames to 4 megapixels. AndroidSVG's existing SVG subset still applies; this does not add script or general SVG/SMIL execution.

`ThumbnailDecoder.decodeViewerFrame` and `CanvasStreams.decodeViewer` return the renderer in BitmapFrame.animation. `CanvasStreams.decodeBytes(bytes, name, targetPixels)` supplies the same discovered codecs and animation handling for in-memory theme assets; its encoded limit is 8 MiB and SVG retains intrinsic document canvas dimensions. Caller owns bitmap/frame presentation and must stop rendering when hidden. Thumbnail requests remain still previews.
