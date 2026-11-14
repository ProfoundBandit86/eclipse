import json, re, sys
raw = json.load(open(sys.argv[1]))
SEC = {'Source Ports & Engine Reimplementations': 'windows', 'Decompilation-Based Ports': 'windows', 'N64: Recompiled': 'n64',
       'Wii U, Wii, GameCube, DS & Switch Recompilations': 'wiiu', 'Xbox 360 Recompilations': 'xbox360',
       'Original Xbox, PlayStation, PSP & PC Recompilations': 'windows', 'Handheld & 16-Bit Recompilations': 'gba',
       'Games on Their Own Runtimes & Launchers': 'windows', 'Classic Ports (Low Activity)': 'windows'}
O = {}
def s(sys_, *ix):
    for i in ix: O[i] = sys_
s('amiga', 1); s('scummvm', 7); s('rpgmaker', 41); s('genesis', 44)
s('dos', 16, 23, 27, 28, 33, 35, 37, 39, 49, 51, 52, 53, 56, 57, 197)
s('gc', 59, 69, 70, 73, 74, 75, 76, 81, 92, 109, 120); s('ps1', 60, 82, 146); s('xbox', 64, 142, 143, 144, 150, 152, 159)
s('ps2', 65, 160); s('gba', 66, 68, 163, 164, 165, 170, 171, 175, 179); s('snes', 67, 166, 177, 198)
s('n64', 71, 72, 77, 80, 88, 89, 91, 199, 201, 207); s('gbc', 78, 79, 169, 172, 174); s('wii', 90, 115, 116, 118)
s('switch', 108, 114, 121); s('nds', 117, 119); s('psp', 147, 148, 149, 151); s('32x', 167); s('nes', 168)
s('gb', 173, 176, 178); s('windows', 162)
out = []
for i, o in enumerate(raw):
    sysid = O.get(i, SEC[o['section']])
    d = o['desc']
    out.append({k: v for k, v in dict(
        id=o['repo'].lower(), name=o['title'], game=o['game'], sys=sysid, repo=o['repo'], author=o['author'], desc=d,
        dual=bool(re.search(r'dual[- ]screen|second[- ]screen|AYN Thor', d, re.I)) or None,
        kind=('recomp' if re.search(r'recompil', o['section'] + d, re.I) else 'decomp' if re.search(r'decompil', o['section'] + d, re.I) else 'port'),
    ).items() if v is not None})
ids = [e['id'] for e in out]
dupes = {x for x in ids if ids.count(x) > 1}
assert not dupes, dupes
cat = {"about": "Native Android ports and recompilations, for Eclipse's Ports & recomps screen. Built from abduznik/awesome-android-native-ports (CC0-1.0), with the system each game came from added. pkg = the app's Android id, when known.",
       "source": "https://github.com/abduznik/awesome-android-native-ports", "version": 1, "ports": out}
json.dump(cat, open(sys.argv[2], 'w'), ensure_ascii=False, indent=1)
from collections import Counter; print(len(out), Counter(e['sys'] for e in out).most_common())
