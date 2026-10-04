# Block Entity Vision 1.2.0 (Fabric, Minecraft 26.3, client-side)

Highlights things through walls, and lets you find any block from a menu.

Needs: Minecraft 26.3, Fabric Loader 0.19.5+, Fabric API 0.161.0+26.3, Java 25.

## Keys (rebindable in Controls)
- B: open the Block Finder menu (search, click blocks to select/deselect, scroll the list)
- G: toggle the automatic block entity highlights (chests, furnaces, spawners...)
- = / -: range +/- 16 blocks (16 to 256, default 64; block search is capped at 128)

## Block Finder
Selected blocks are highlighted in their own color. Pick as many as you like.
"Clear all" removes every selection. Very common blocks (stone, dirt) will hit a
4000-block limit, and only the nearest matches are shown.

## Build with GitHub Actions
Push this folder to a GitHub repo (including .github/workflows/build.yml). The Actions tab
builds it and publishes the jar as the "bevision-jar" artifact.
