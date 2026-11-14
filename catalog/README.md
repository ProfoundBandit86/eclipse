# Catalogs fetched by Eclipse

## ports.json (Ports & recomps)

Native Android ports and recompilations, built from
[abduznik/awesome-android-native-ports](https://github.com/abduznik/awesome-android-native-ports) (CC0-1.0) with the
system each game came from added (`sys`, an Eclipse system id from assets/systems.json). Eclipse fetches this file from
the main branch at most once a day; a copy ships in the app (app/src/main/assets/ports/ports.json).

To rebuild it from a newer copy of the list:

    python3 catalog/tools/parse.py <path to their README.md> raw.json
    python3 catalog/tools/build.py raw.json catalog/ports.json
    cp catalog/ports.json app/src/main/assets/ports/ports.json

build.py gives each entry a system by the list's section, with overrides by position (`s(...)` lines): check new
entries and adjust those. Optional per entry: `pkg`, the app's Android id, which makes "installed" detection exact.
