# Making your own view types and themes

Everything below is plain files you edit on the device (or on a computer and copy over). Eclipse reloads them when
you save. A view type with a mistake just doesn't appear in the list (see "Checking your work" below).

| You want to add… | It's a… | You write… | Guide |
| --- | --- | --- | --- |
| A new way to lay out a games list | **View type** | one `view.json` | [VIEWS.md](VIEWS.md) |
| A new look: colours, wallpapers, icons | **Theme** | `theme.json` + pictures | the end of this page |

Users can move and hide the main screens (Search, Recent, Systems, Apps) in the left dock:
**Settings → General → Left dock → Pages**.

## A view type, in five minutes

1. Copy `examples/views/gentle-flow.json` to `/storage/emulated/0/Eclipse/views/` (make the folder if it isn't there).
2. Open any system's games, press R, Options → View. **Gentle flow** is in the list.
3. Edit the numbers in the file and save. Eclipse re-reads it within two seconds.

The whole file format, with every name you can use in the formulas, is in [VIEWS.md](VIEWS.md).

## A theme

1. In Eclipse: Settings → Theme → **Save a template**. It writes `Eclipse theme template.zip` to
   Download, with a README that lists every system's name for naming icons. Or start from
   `examples/themes/midnight/theme.json`.
2. Put your files next to `theme.json`, in a folder named after the theme, inside `/storage/emulated/0/Eclipse/Themes/`
   (or zip it and use Settings → Theme → Import).

```
Eclipse/Themes/midnight/
  theme.json        colours and options (below)
  wallpaper.jpg     top screen (1920 x 1080 looks best)
  bottom.jpg        bottom screen
  icons/snes.png    a picture per system, named after it (square, about 512 x 512)
  backdrops/snes.jpg  optional: shown behind a system while it is highlighted
  dock/home.png     optional: home, games, romm, apps, settings
  font.ttf          optional
```

`theme.json`, every field optional:

| Field | Meaning |
| --- | --- |
| `name`, `author`, `description` | shown in the theme list |
| `accent`, `focus` | gold-by-default highlight colour, and the outline colour (`#RRGGBB`) |
| `text`, `labels` | main writing, and softer labels |
| `glass`, `glassStrength` | tint of the frosted panels, and how solid they are (0 to 100) |
| `dim` | how much the wallpaper is darkened (0 to 100) |
| `wallpaper`, `bottomWallpaper` | picture file names |
| `wallpaperMode` | `"fallback"`: only where a game has no backdrop of its own; `"always"` |
| `font` | a `.ttf` / `.otf` next to the file |

Picking a theme resets your own colour choices; anything the theme leaves out stays as it was.

## Checking your work

| Symptom | Look at |
| --- | --- |
| A view isn't in the View list | `views/` is at `/storage/emulated/0/Eclipse/views/` and the file ends in `.json` |
| A view looks wrong | open the file: a formula Eclipse can't read leaves the view out of the list; see [VIEWS.md](VIEWS.md) → Mistakes |
| Theme colours don't change | `#RRGGBB` with the `#`, and re-pick the theme |
