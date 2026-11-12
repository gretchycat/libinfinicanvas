# SVG artwork support

SVG and SVGZ are supported as **static artwork** through AndroidSVG. Experimental rendering of `https://infinicanvas.org/svg` palette/cycle metadata has been removed at the owner's request. Such metadata is ignored; explicit standard SVG fills, strokes and gradients determine the still image. An element with only a custom palette binding uses standard SVG's default paint, so authoring files should include ordinary fallback paints when used directly.

`CanvasStreams.decodeBytes` preserves intrinsic document canvas dimensions, bounds output to the requested size without upscaling, and returns no SVG animation state. Viewer/thumbnail rendering retains its existing static SVG sizing and AndroidSVG subset limits. Source SVG files are unchanged.

IFF CRNG/CCRT/DRNG cycling remains supported. The SVG-to-IFF authoring converter is separate from rendering and remains available in Infinifolder's scripts. Other supported animated image formats are unchanged.
