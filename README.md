# Lyd Model Mobile

A touchscreen-first Android 3D modelling app with a hardware-accelerated OpenGL ES viewport, focused on printable designs and STL export.

## Current MVP
- Kotlin + Jetpack Compose, Android 16 target (API 36), min API 26.
- Touch-oriented, responsive editor with an OpenGL ES 2.0 perspective 3D renderer, depth testing, mesh geometry and directional lighting.
- Collapsible tools panel for a less cluttered canvas.
- Adjustable side-panel width on wider layouts.
- Full-screen focus mode that keeps the model workspace front and centre.
- Add cube, sphere and cylinder primitives.
- Orbit the camera by dragging empty workspace; pinch to zoom.
- Real triangle meshes for cubes, spheres and cylinders, rendered with a depth buffer.
- Perspective camera, directional shading and a 3D workplane grid.
- Camera direction presets: top, bottom, front, back, left, right and home/isometric.
- Select and drag objects on the workplane; perspective projection updates as the camera rotates.
- Adjust width, depth and height in millimetres.
- Duplicate and delete objects.
- Export combined geometry as an ASCII STL using Android's document picker.

## Build
Open this repository in Android Studio with JDK 17 and Android Gradle Plugin 8.9.1. Allow Gradle sync, then run the app on an Android device or emulator with API 26+.

## Roadmap
1. Camera pan, draggable view cube, grid snapping, and improved object hit-testing.
2. Undo/redo command history and native project save/open.
3. Boolean union/subtract/intersect and mesh validation.
4. Import STL/OBJ, grouping, alignment, precise numeric transforms and measurement tools.
5. Binary STL export, quality presets and printability checks.

The viewport uses a hardware-accelerated OpenGL ES 2.0 renderer with perspective projection, depth testing, lit triangle meshes, orbit controls and camera presets. The current renderer supports cube, sphere and cylinder primitives; advanced mesh editing and boolean operations are not yet implemented. The STL exporter independently generates triangle meshes for the supported primitives.
