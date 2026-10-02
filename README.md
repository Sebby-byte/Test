# Block Entity Vision (Fabric, 1.21.4, client-side)

Draws translucent colored boxes through walls on nearby block entities.

## Build
1. Install JDK 21.
2. Run `gradle wrapper` once (or copy a Gradle wrapper in), then `./gradlew build`.
3. Jar ends up in `build/libs/bevision-1.0.0.jar`. Drop it in `.minecraft/mods` with Fabric Loader + Fabric API.

## Dev testing
`./gradlew runClient`

## Keys (rebindable in Controls)
- G: toggle on/off
- = / -: range +/- 16 blocks (16 to 256)

## Colors
Trapped chest red, chest orange, ender chest purple, barrel brown, shulker pink,
furnaces gray, hopper dark gray, dispenser/dropper blue, brewing stand teal, spawner bright red.

## Build without installing anything (GitHub Actions)
1. Create a new GitHub repo and upload everything in this folder (including the hidden `.github` folder).
2. Open the repo's Actions tab, wait for the "build" run to finish (a few minutes).
3. Click the run, scroll to Artifacts, download `bevision-jar` and unzip it to get the jar.
