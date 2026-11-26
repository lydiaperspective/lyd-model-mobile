# Lyd Model Mobile

A touchscreen-first Android 3D modelling app focused on printable designs and STL export.

## Current MVP
- Kotlin + Jetpack Compose, Android 16 target (API 36), min API 26.
- Touch-oriented, responsive editor layout with an isometric projected workspace.
- Collapsible tools panel for a less cluttered canvas.
- Adjustable side-panel width on wider layouts.
- Full-screen focus mode that keeps the model workspace front and centre.
- Add cube, sphere and cylinder primitives.
- Select and drag objects on the workplane; pinch to zoom.
- Adjust width, depth and height in millimetres.
- Duplicate and delete objects.
- Export combined geometry as an ASCII STL using Android's document picker.

## Build
Open this repository in Android Studio with JDK 17 and Android Gradle Plugin 8.9.1. Allow Gradle sync, then run the app on an Android device or emulator with API 26+.

## Roadmap
1. True perspective 3D viewport with orbit/pan controls and grid snapping.
2. Undo/redo command history and native project save/open.
3. Boolean union/subtract/intersect and mesh validation.
4. Import STL/OBJ, grouping, alignment, precise numeric transforms and measurement tools.
5. Binary STL export, quality presets and printability checks.

The current viewport is a lightweight isometric Compose rendering for the initial editor foundation; the STL exporter creates triangle meshes for the supported primitives.
