# SimCity (Paper 1.21.x) — v0.1.8

## Build (macOS)
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
java -version

cd ./simcity-paper

# If the Gradle wrapper files exist in this zip:
chmod +x gradlew
./gradlew build

# If they do NOT exist on your copy:
# (Install Gradle once via Homebrew, then generate wrapper)
# brew install gradle
gradle wrapper
chmod +x gradlew
./gradlew build
```

## Deploy
- Stop your server.
- Delete any older SimCity jars from `plugins/`.
- Copy `build/libs/simcity-paper-0.1.8.jar` to `plugins/`.
- Start the server.
- `/version SimCity` should show `0.1.8`.
```
/city create MyTown
/city wand
# Left click Pos1, right click Pos2
/city ymode HEIGHTS
/city addcuboid mytown
/city stats
/city scoreboard on
```
