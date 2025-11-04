# CitySim (Paper 1.21.x) — v0.5.3
SimCity-like city stats for villagers (Paper).

## What it does
CitySim lets you turn villager settlements into named "cities" with live stats. It keeps an eye on each city so you know how your
villagers are doing—covering population, jobs, beds, lighting, green space, travel options, pollution, and crowding. The plugin
shares these numbers in easy-to-read displays so players can instantly tell whether a city is thriving or needs attention.

### Key features
- Define cities as one or more cuboids and save them for future server restarts.
- Automatic scans that roll villager activity and city blocks into a simple happiness score with a clear breakdown.
- `/city stats` chat report that explains where points are gained or lost for the city you are in (or another by ID).
- Boss bar, scoreboard (compact or full), and entry title overlays that players can toggle whenever they like.
- PlaceholderAPI expansion (`%citysim_*%`) for plugging stats into other plugins or custom scoreboards.
- Admin commands to rename cities, adjust cuboids, switch highrise mode, and set the number of transit stations.

## City creation flow
1. **Get the selection wand** – `/city wand` gives you the golden axe used to pick corners. Left-click sets corner 1, right-click
   sets corner 2. Use `/city wand clear` to reset the selection or `/city wand ymode <full|span>` to choose whether the cuboid
   spans the entire world height (`full`, the default) or only the Y span you select (`span`).
2. **Create the city** – Once both corners are selected, run `/city create <name>`. CitySim checks that the box sits in your
   current world and saves it as a new city. No selection ready yet? Run `/city create` anyway and add the shape afterwards.
3. **Add more areas (optional)** – Stand in the next spot, mark its corners with `/city wand`, then run `/city edit <cityId>
   cuboid add` (or the shortcut `/city expand <cityId>`). Grab the `cityId` from `/city list`. To shrink the city, stand in the
   chunk to remove and use `/city edit <cityId> cuboid remove`.
4. **Tune city settings** – `/city edit <cityId> name <new name>` renames the city. `/city edit <cityId> highrise <true|false>`
   flips the highrise switch (loosens crowding limits for tall builds). `/city edit <cityId> station <add|remove|set|clear>
   <amount>` changes how many transit stations the score counts.
5. **Keep an eye on things** – `/city stats [cityId]` shows the latest population, jobs, beds, station count, overall happiness,
   and every boost or penalty. `/city top [happy|pop]` ranks the most cheerful or most crowded cities.

## Player HUD controls
Players can opt in or out of the different overlays at any time:
- `/city display bossbar on|off` – toggle the progress-style bar across the top of the screen.
- `/city display titles on|off` – show or hide the welcome banner that appears when entering a city.
- `/city display scoreboard <off|compact|full>` – switch off the sidebar, show just the core stats, or expand to the full happiness breakdown.

Preferences persist automatically so players keep their chosen HUD on reconnect.

## Build

### macOS / Linux
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)  # macOS helper; on Linux point this to your JDK 21 install
export PATH="$JAVA_HOME/bin:$PATH"
java -version  # should report a Java 21 runtime

chmod +x gradlew
./gradlew clean build
```

### Windows (PowerShell)
```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version  # should report a Java 21 runtime

.\gradlew.bat clean build
```

## Deploy
- Stop your server.
- Delete any older CitySim jars from `plugins/`.
- Copy `build/libs/CitySim-0.5.3.jar` to `plugins/`.
- Start the server.
- `/version CitySim` should show `0.5.3`.

## Commands
```text
/city wand [clear]                               # get the golden axe for marking corners (use clear to reset the selection)
/city create <name>                              # make a new city, using your current selection if one is ready
/city add <name>                                 # alias of /city create for players used to the old wording
/city list                                       # show every saved city with its ID
/city remove <cityId>                            # delete a city and all of its tracked areas (admin only)
/city edit <cityId> name <new name>              # rename an existing city (admin only)
/city edit <cityId> cuboid add                   # add your current selection as another chunk of the city (admin only)
/city edit <cityId> cuboid remove                # remove the chunk you are standing in from the city (admin only)
/city edit <cityId> cuboid list                  # list every cuboid and its bounds for that city (admin only)
/city edit <cityId> cuboid show [on|off]         # toggle the live particle preview of a city's cuboids (admin only)
/city expand <cityId>                            # shortcut for /city edit <cityId> cuboid add (admin only)
/city edit <cityId> highrise <true|false>        # relax or tighten crowding limits for tall builds (admin only)
/city edit <cityId> station <add|remove|set|clear> [amount]  # adjust how many transit stations the city counts (admin only; available when `stations.counting_mode` is `manual` in config.yml)
/city wand ymode <full|span>                     # choose whether selections cover the full world height or just the Y range you click
/city stats [cityId]                             # see population, jobs, beds, stations, and the full happiness breakdown
/city display titles on|off                      # show or hide the entry banner for your HUD
/city display bossbar on|off                     # toggle the boss bar HUD element
/city display scoreboard <off|compact|full>      # pick the scoreboard style or turn it off entirely
/city top [happy|pop]                            # rank cities by happiness or population
/city reload                                     # reload the plugin configuration (admin only)
/city debug scans                                # print scan timing details to chat for troubleshooting (admin only)
```

## Integrations

### [TrainCarts](https://modrinth.com/plugin/traincarts) station sync
CitySim can keep the transit stat in step with your TrainCarts rail network. Set `stations.counting_mode: traincarts` in
`config.yml` to enable the integration. When this mode is active, the plugin scans the `station` signs that fall inside each
city’s cuboids and updates the station total automatically. Manual `/city edit <cityId> station ...` commands are disabled in
this mode so the TrainCarts counts stay authoritative, and CitySim refreshes the totals whenever TrainCarts is enabled, reloaded,
or disabled.

**Requirements:** Install both [TrainCarts](https://modrinth.com/plugin/traincarts) and [Vault](https://modrinth.com/plugin/vaultunlocked) before switching the counting mode. CitySim relies on Vault being loaded to
bootstrap the integration; if either plugin is missing, it falls back to manual station counts until they are present.

## Configuration (`config.yml`)
CitySim drops a `config.yml` file in `plugins/CitySim/`. The shipped defaults work well for most servers, but everything is open
for tweaking:

- **`updates`** – Controls how frequently stats and HUD elements refresh and how much work each scan performs.
  - `stats_interval_ticks` / `stats_initial_delay_ticks` – Interval and initial delay (ticks) between stat refreshes.
  - `bossbar_interval_ticks` – How often the boss bar display is updated.
  - `max_cities_per_tick`, `max_entity_chunks_per_tick`, `max_bed_blocks_per_tick` – Workload caps that keep scans lightweight.
- **`visualization`** – Controls the new particle renderer that powers wand selections and `/city edit <id> cuboid show`.
  - `enabled` – Master switch for the visualizer.
  - `particle` / `dust_color` – Choose the particle type (default `DUST`) and color when using dust.
  - `view_distance` – Skip rendering when a player is farther than this many blocks from a shape.
  - `base_step` / `far_distance_step_multiplier` – Baseline sampling density and how aggressively it thins out with distance.
  - `max_points_per_tick` – Per-player emission budget; geometry coarsens automatically to stay within this cap.
  - `refresh_ticks` – How often each player's outline task runs.
  - `async_prepare` – Toggle asynchronous geometry preparation.
  - `jitter` – Small random offset to reduce aliasing on long edges.
  - `slice_thickness` – Optional vertical thickness for the Y-mode `full` slice.
  - `face_offset` – Slightly expands shapes to keep particles from clipping into blocks.
  - `corner_boost` – Adds extra particles to edges and corners so outlines look crisp.
  - `debug` – When `true`, logs extra detail about visualizer behavior for troubleshooting.

- **Visualization performance notes** – With the defaults (`max_points_per_tick: 800`, `refresh_ticks: 3`) each player receives at most ~266
  particles per tick. Typical selections such as a 32×32×32 span resolve to ~250 particles, while a 512×512 full-height region automatically
  thins to ~780 points so it stays inside the budget. Cached outlines are stored per player/city pair as compact coordinate arrays (roughly
  120 bytes per 100 particles), keeping even a city with a dozen cuboids under 100 KiB of visualization cache.
- **`stations.counting_mode`** – Default is `manual`. Change to `traincarts` for automatic station syncing or `disabled` to
  ignore station scoring entirely.
- **`migration`** – Governs the automated villager migration system, including teleport safety rules.
  - `teleport.require_wall_sign` – Defaults to `true`. When enabled, migration only considers TrainCarts stations that use wall-mounted signs for teleport anchors. Set this to `false` if your network relies on standing/post signs so they are eligible; the plugin logs a warning when no wall-sign stations are found and the restriction blocks every candidate.

### Troubleshooting migration targets
- Migration freshness checks now look at the newest timestamp across the city stats row and every populated scan cache (block + entity). If one cache lags behind, the DEBUG log prints each timestamp and which one was chosen so you can spot the stale source. Adjust `migration.logic.freshness_max_secs` if your scan cadence is intentionally slower.
- If your TrainCarts stations rely on standing or frame signs, set `migration.teleport.require_wall_sign: false`. When the flag stays true and only non-wall signs exist, the resolver emits an INFO hint explaining that platform caching is blocked by the requirement.
- When no prevalidated platforms are in cache (for example after a station rebuild), the fallback sampler automatically widens to the configured `migration.teleport.radius` so villagers can still land on a safe, non-rail floor that honors every existing safety rule.
- **`happiness_weights`** – Sets the maximum points (or penalties) each stat contributes to the happiness score.
- **`titles`** – Enables or disables entry titles, sets the cooldown, and defines the MiniMessage text players see in different
  situations (keep `{city}` as the placeholder for the city name).
