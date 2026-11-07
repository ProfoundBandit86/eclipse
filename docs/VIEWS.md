# Writing a view type

A view type is one JSON file that says **where every card goes** and **what else is drawn**. Eclipse does the rest:
controller and touch, focus, loading artwork, the game page, long-press menu and badges. A card in your view is a normal
tile (a game's cover, or a system's card), so it behaves like one.

A view can be made for any of three screens, chosen with `"screens"` (below): a system's **games**, Home's
**Recently Played** row, and Home's **Systems** row.

**Installing one:** Settings → **Views** → Install a view type, and pick a `.json` (or a `.zip` holding several). Eclipse
reads each one first and tells you what's wrong if it can't. Or copy the file into `/storage/emulated/0/Eclipse/views/`
yourself (any name ending in `.json`, or `views/<name>/view.json`). Settings → Views also turns any view type on or off.

**Trying it:** press R on a screen it's made for → **View**: yours is in the list. Save the file and Eclipse picks the
change up within two seconds, on the real list. Start from `examples/views/gentle-flow.json` (a cover flow) or
`examples/views/ladder.json` (a vertical list with the game's logo and name beside it).

## The file

```json
{
  "id": "gentle-flow",
  "name": "Gentle flow",
  "screens": ["games", "recent"],
  "step": 0,
  "window": 6,
  "corner": 8,
  "art": "box",
  "card": { "width": "h*0.52*0.75", "height": "h*0.52" },
  "transform": {
    "x": "d*w*0.15 + sign(d)*w*0.06",
    "y": "0",
    "scale": "1 - min(abs(d), 3)*0.12",
    "rotateY": "clamp(-d*38, -55, 55)",
    "rotateX": "0",
    "alpha": "1 - min(abs(d), 4)*0.2",
    "z": "-abs(d)"
  },
  "slots": [
    { "kind": "backdrop", "blur": 18, "dim": 0.55 },
    { "kind": "title", "x": "w/2", "y": "h*0.86", "align": "center", "size": 18 }
  ]
}
```

| Key | Meaning |
| --- | --- |
| `id` | unique name for the view (letters, digits, dashes). Default: the file name |
| `name` | what the View list shows |
| `screens` | where it's offered: any of `"games"` (a system's games), `"recent"` (Recently Played), `"systems"` (Home's systems row). Default: `["games"]` |
| `step` | how many games **Up / Down** move. `0`: Up / Down leave the view (like the slide view). `1`: a vertical list. A number like `5` suits a grid-like wall |
| `window` | how many covers either side of the highlighted one are drawn (1 to 60, default 8). Fewer is faster |
| `corner` | roundness of each cover, in dp |
| `card.width`, `card.height` | size of every cover, in dp |
| `transform` | where each cover goes: formulas, below. Every one is optional |
| `slots` | extra things drawn: backdrop, title, logo, meta |

Left / Right move to the next cover by position, so lay covers out in a row or a grid and the controller just works.

## Formulas

Every value in `card`, `transform` and a slot's `x` / `y` is a formula, written as text. It is worked out for each cover,
and animated when the highlight moves.

**Names you can use**

| Name | Meaning |
| --- | --- |
| `d` | how far this cover is from the highlighted one: `0` highlighted, `-1` the one before it, `1` the one after |
| `i` | this cover's position in the list (0 is the first game) |
| `s` | `1` for the highlighted cover, `0` for the others |
| `n` | how many games there are |
| `w`, `h` | the width and height of the area the view has, in dp (the screen below the header) |
| `pi` | 3.14159 |

**Operators:** `+  -  *  /  %` and brackets. **Functions:** `min(a,b)  max(a,b)  abs(a)  clamp(a, low, high)  floor(a)  ceil(a)
round(a)  sqrt(a)  pow(a,b)  sin(deg)  cos(deg)  sign(a)`. Angles are in degrees. Numbers can be written `0.5`.

**What each `transform` value means**

| Key | Unit | Meaning |
| --- | --- | --- |
| `x`, `y` | dp | offset from the **centre** of the area. `x: "d*120"` puts covers 120 dp apart in a row; negative is left / up |
| `scale` | 1 = normal | size multiplier |
| `rotateY`, `rotateX` | degrees | turn around the vertical / horizontal axis (3D tilt) |
| `alpha` | 0 to 1 | see-through; `0` is invisible |
| `z` | any number | drawing order: bigger is in front |

## Slots

A slot is drawn besides the cards, about the **highlighted** one. `backdrop` slots go behind the cards; the rest go on top.

| `kind` | On games and Recently Played | On Systems | Keys it uses |
| --- | --- | --- | --- |
| `backdrop` | the game's backdrop, filling the area | the system's background | `blur` (dp), `dim` (0 to 1, how dark) |
| `title` | the game's name | the system's name | `x`, `y`, `align`, `size` (sp), `color` |
| `meta` | its system's name in small capitals | how many games it has | `x`, `y`, `align`, `size`, `color` |
| `logo` | the game's logo (its name if it has none) | the system's logo | `x`, `y`, `align`, `size` (height in dp) |

On the Systems row, `n` is the number of systems and `i` a system's place in the row; system cards are portrait like
covers, so the same `card` sizes work.

`x` and `y` are from the **top-left** of the area (not the centre), as formulas with `w` and `h`. `align` is `left`
(`x` is where it starts), `center` (`x` is the middle) or `right` (`x` is where it ends). `color` is `"#RRGGBB"`.

## Recipes

- **A plain row:** `"x": "d*w*0.14"`, `"scale": "1 + s*0.25"`.
- **Cover flow:** add `"rotateY": "clamp(-d*40, -60, 60)"` and `"z": "-abs(d)"`.
- **A wall of 5 columns** that slides so the highlighted cover stays in the middle: `"step": 5`, `"window": 15`,
  `"card": { "width": "w*0.15", "height": "h*0.26" }`, and
  `"x": "((i%5) - ((i-d)%5))*w*0.18"`, `"y": "(floor(i/5) - floor((i-d)/5))*h*0.3"`.
  (`i - d` is the highlighted game's position, so its column is `(i-d)%5` and its row is `floor((i-d)/5)`; each cover is
  placed relative to that.)
- **Fade the far ones:** `"alpha": "1 - min(abs(d), 4)*0.25"`.
- **Bigger highlighted cover:** `"scale": "1 + s*0.3"` and `"z": "s"`.
- **Details on the left, covers on the right:** put the `x` of the covers at `w*0.25 + d*w*0.14` and a `title`
  slot at `"x": "w*0.05"`.

## Mistakes

| What you see | Why |
| --- | --- |
| The view isn't in the list | the file isn't valid JSON (a missing comma or quote), or a formula can't be read (Settings → Views names the file and the problem). Fix and save: it appears within two seconds |
| It's in one screen's list but not another's | add that screen to `"screens"` |
| It's in no list at all | it's turned off in Settings → Views |
| Covers sit on top of each other | `x` or `y` doesn't use `d` or `i`; every cover gets the same place |
| Covers are far off screen | `x`/`y` are from the centre in dp, and the area is about 750 x 370 dp on the AYN Thor. Use `w` and `h` instead of fixed numbers |
| Up / Down do nothing | `step` is `0`. Set it to the number of games a row holds |
| The highlighted cover is behind its neighbours | give it a bigger `z` (`"z": "s"`) |
| Stutters | lower `window` |
| Wrong name in a formula | only `d i s n w h pi` and the functions above exist |

If a view file is later deleted, broken or turned off while it's the chosen one, that screen shows the first view that's
still on.
