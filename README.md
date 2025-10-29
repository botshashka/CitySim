# CitySim (Paper 1.21.x) — v0.4.8
SimCity-like city stats for villagers (Paper).

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
```
/city create MyTown
/city wand
# Left click Pos1, right click Pos2
/city wand ymode full
/city edit mytown addcuboid
/city stats
/city display scoreboard full
```
