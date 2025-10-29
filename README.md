# CitySim (Paper 1.21.x) — v0.4.8
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
- Copy `build/libs/citysim-paper-0.4.8.jar` to `plugins/`.
- Start the server.
- `/version CitySim` should show `0.4.8`.

### Commands
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
/city edit <cityId> station <add|remove|set|clear> [amount]  # adjust how many transit stations the city counts (admin only)
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
CitySim drops a `config.yml` file in `plugins/CitySim/`. The defaults work for most servers, but you can tailor a few things:

- **Update pacing** – `updates.*` lets you decide how quickly the plugin refreshes stats and HUD elements, and how much work it
  does each tick when scanning cities. Raise the intervals for a quieter server, or ease them down if you want snappier
  updates.
- **Score weighting** – `happiness_weights.*` sets the maximum points (or penalties) each factor contributes to the happiness
  score. Bump a value up if you want that stat to matter more, or lower it to soften its impact.
- **Entry titles** – `titles.enabled` flips the welcome banner on or off globally, `titles.cooldown_ticks` controls how soon it
  can reappear, and the `titles.messages.*` sections hold the MiniMessage strings players see for each situation. Feel free to
  swap in your own wording while keeping `{city}` as the placeholder for the city name.
