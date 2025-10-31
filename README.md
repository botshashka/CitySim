# CitySim (Paper 1.21.x) — v0.5.1
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
   addcuboid`. Grab the `cityId` from `/city list`. To shrink the city, stand in the chunk to remove and use `/city edit <cityId>
   removecuboid`.
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
- Copy `build/libs/CitySim-0.5.1.jar` to `plugins/`.
- Start the server.
- `/version CitySim` should show `0.5.1`.

## Commands
```text
/city wand [clear]                               # get the golden axe for marking corners (use clear to reset the selection)
/city create <name>                              # make a new city, using your current selection if one is ready
/city add <name>                                 # alias of /city create for players used to the old wording
/city list                                       # show every saved city with its ID
/city remove <cityId>                            # delete a city and all of its tracked areas (admin only)
/city edit <cityId> name <new name>              # rename an existing city (admin only)
/city edit <cityId> addcuboid                    # add your current selection as another chunk of the city (admin only)
/city edit <cityId> removecuboid                 # remove the chunk you are standing in from the city (admin only)
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

## Configuration (`config.yml`)
CitySim drops a `config.yml` file in `plugins/CitySim/`. The shipped defaults work well for most servers, but everything is open
for tweaking:

- **`updates`** – Controls how frequently stats and HUD elements refresh and how much work each scan performs.
  - `stats_interval_ticks` / `stats_initial_delay_ticks` – Interval and initial delay (ticks) between stat refreshes.
  - `bossbar_interval_ticks` – How often the boss bar display is updated.
  - `max_cities_per_tick`, `max_entity_chunks_per_tick`, `max_bed_blocks_per_tick` – Workload caps that keep scans lightweight.
- **`selection`** – Governs the golden-axe preview particles and `/city edit ... showcuboids` outlines.
  - `max_outline_particles` – Maximum particle count for the detailed edge loop. If a cuboid would exceed this number, the plugin
    switches to light-weight corner columns (with optional mid-edge markers) so huge selections stay readable without flooding
    the client.
  - `simple_outline_midpoints` – When the simplified outline is active, toggles the extra mid-edge markers that help players
    gauge width and depth.
- **`stations.counting_mode`** – Default is `manual`. Change to `traincarts` for automatic station syncing or `disabled` to
  ignore station scoring entirely.
- **`happiness_weights`** – Sets the maximum points (or penalties) each stat contributes to the happiness score.
- **`titles`** – Enables or disables entry titles, sets the cooldown, and defines the MiniMessage text players see in different
  situations (keep `{city}` as the placeholder for the city name).

## Integrations

### [TrainCarts](https://modrinth.com/plugin/traincarts) station sync
CitySim can keep the transit stat in step with your TrainCarts rail network. Set `stations.counting_mode: traincarts` in
`config.yml` to enable the integration. When this mode is active, the plugin scans the `station` signs that fall inside each
city’s cuboids and updates the station total automatically. Manual `/city edit <cityId> station ...` commands are disabled in
this mode so the TrainCarts counts stay authoritative, and CitySim refreshes the totals whenever TrainCarts is enabled, reloaded,
or disabled.

**Requirements:** Install both [TrainCarts](https://modrinth.com/plugin/traincarts) and [Vault](https://modrinth.com/plugin/vaultunlocked) before switching the counting mode. CitySim relies on Vault being loaded to
bootstrap the integration; if either plugin is missing, it falls back to manual station counts until they are present.
