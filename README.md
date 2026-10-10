# Auto Attack 1.2.0 (Fabric, Minecraft 26.3, client-side)

Two toggleable features, for hostile mobs and other players (never animals):

- **Auto Click** (V): presses your Attack key (left click) while your crosshair is on a hostile
  mob or another player within normal reach. Waits for the attack cooldown. Sends no attack commands of its own.
- **Auto Rotate** (R): turns your view toward the nearest hostile mob or player within the set range (1-45 blocks, default 6) that you
  have a clear line of sight to.
- **Settings** (U): toggle buttons plus a slider for rotation speed (1 to 45 degrees per tick,
  default 10). The slider value is saved.

Both features start OFF every launch. Keys are rebindable in Controls.

## Where it works
- Singleplayer: always.
- Multiplayer: only servers listed in `allowedServers` in `config/autoattack.properties`
  (default: localhost, 127.0.0.1). Anywhere else it refuses to turn on.

On a multiplayer server, press U and click **Allow this server** (no file editing needed), or edit the file by hand.

Only use this where automation is explicitly permitted. Most public servers prohibit it.

Needs: Minecraft 26.3, Fabric Loader 0.19.5+, Fabric API 0.161.0+26.3, Java 25.
