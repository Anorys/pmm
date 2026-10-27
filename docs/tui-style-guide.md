# pmm TUI style guide

The visual rules of `pmm ui`. Every color, symbol and spacing used by the dashboard comes from
`com.anorys.pmm.tui.theme.Theme`; views never hard-code a color. Change the theme there, and the whole
UI follows.

## Principles

The dashboard follows the Anorys visual identity (the "Atlas dense Anorys" chart, dark mode): green to
act, grey to structure, one color and one symbol per state.

1. **Status first.** The eye should land on what is broken before anything else: errors are the only
   warm saturated colors on screen, healthy rows stay quiet.
2. **Grey structures, green acts.** Large areas use the grey neutrals; the Anorys greens mark what you
   can act on (the selection, the active tab, the focused panel, progress).
3. **The orange point is rare.** As in the logo, the orange `#FF7A1A` marks the one thing happening now:
   the selected row's marker and the logo. Never a state color.
4. **Every state has a symbol and a word.** Color is never the only signal (color blindness, 16-color
   terminals, screenshots in an e-mail).
5. **Keys are always visible.** The footer always shows the keys that work on the current screen.

## Palette

The UI paints its own background, so it looks the same in any terminal theme.

### Neutrals (grey structures)

| Token | Hex | Use |
|---|---|---|
| `CRUST` | `#171919` | Header and footer bars (Anorys dark surface) |
| `MANTLE` | `#1B1D1D` | Panels inside the main area |
| `BASE` | `#202323` | Main background (Anorys dark background) |
| `SURFACE1` | `#303535` | Borders of unfocused panels, key chips (Anorys dark line) |
| `SURFACE0` | `#163631` | Selected row (Anorys dark accent-soft) |
| `OVERLAY` | `#6E7875` | Muted text: hints, units, separators |
| `SUBTEXT` | `#A2ABA8` | Secondary text: paths, versions (Anorys dark muted) |
| `TEXT` | `#E3E7E6` | Primary text (Anorys dark text) |

### Anorys greens (acting) and the orange point

| Token | Hex | Use |
|---|---|---|
| `FACET_DARK` | `#08473F` | Logo, dark facet |
| `FACET` | `#0E7A6B` | Logo, Anorys green; start of the brand gradient |
| `FACET_LIGHT` | `#17A58F` | Logo, light facet; middle of the brand gradient |
| `ACCENT` | `#4FC1B1` | Focus, active tab, key letters, running tasks |
| `ON_ACCENT` | `#06201C` | Text on an `ACCENT` background |
| `SPARK` | `#FF7A1A` | The logo's orange point; the selected row's marker |

The brand gradient runs `FACET` → `FACET_LIGHT` → `ACCENT` and back, so that it can loop when animated.

### States

| State | Token | Hex | Symbol | ASCII | Word |
|---|---|---|---|---|---|
| OK, up to date, released | `GREEN` | `#5FD39A` | `✔` | `+` | `ok` |
| Cached build | `GREEN` | `#5FD39A` | `✔` | `+` | `cached` |
| Warning, behind, outdated | `AMBER` | `#F0A766` | `▲` | `!` | `warn` |
| Error, failed build | `RED` | `#F07178` | `✘` | `x` | `error` |
| Blocked by another repository | `VIOLET` | `#BFA0F2` | `⊘` | `#` | `blocked` |
| Information | `BLUE` | `#86B3F2` | `●` | `i` | `info` |
| Running | `ACCENT` | `#4FC1B1` | spinner | `*` | `building` |
| Queued, skipped, unknown | `OVERLAY` | `#6E7875` | `◌` | `.` | `queued` |

Amber, violet and blue come from the Anorys node-type colors (dark mode); red is added for errors, at
the same lightness. Git counters: `↑3` commits ahead (`BLUE`), `↓12` commits behind (`AMBER` once
flagged by `git.behind`, else `SUBTEXT`), `✎ 4` local changes (`AMBER`).

## Typography

Terminals have one font (the Anorys chart uses Signika, Heebo and JetBrains Mono; a terminal only has the
last kind), so hierarchy comes from weight, color and case:

- **Panel titles**: bold, `TEXT`, with a leading `ACCENT` glyph `◆`.
- **Column headers**: bold, `OVERLAY`, upper case.
- **Values**: regular `TEXT`; secondary values in `SUBTEXT`.
- **Hints and units**: `OVERLAY`, never bold.
- **Badges**: upper case word on a colored background with `CRUST` text, one space of padding on each
  side (` OK `, ` BLOCKED `).

## Components

- **Header** (3 rows, `CRUST`): the faceted hexagon mark (three greens, orange point), the `pmm`
  wordmark in the brand gradient, the workspace path, the global health gauge and the time of the last fetch.
- **Tabs**: `1 Workspace  2 Build  3 Dependencies  4 Graph`. The active tab is bold `ACCENT` with a gradient
  underline; the others are `OVERLAY`.
- **Panels**: rounded borders (`╭╮╰╯`) in `SURFACE1`; the focused panel's border is `ACCENT`.
- **Tables**: no inner grid lines, one empty column between columns, selected row on `SURFACE0` with a
  `▌` marker in `SPARK` orange on the left.
- **Footer** (1 row, `CRUST`): key chips (`key` in `ACCENT` on `SURFACE1`, then the action in `SUBTEXT`).
- **Toasts**: one line above the footer, colored by severity, dismissed after 5 seconds.
- **Dialogs**: centered, rounded `ACCENT` border on `MANTLE`, with the actions as key chips. Any action
  that rewrites history (rebase, force push) asks first.
- **Progress**: line gauges in the brand gradient; spinners use the braille dots set.

## Motion

Animation tells the user that something is running, nothing more: spinners for running tasks, a
smooth fill for progress bars, and the wordmark gradient shifting slowly while a build runs. Nothing moves
when the workspace is idle.

## Fallbacks

- `PMM_ASCII=1` replaces every symbol with its ASCII column above (old consoles, non UTF-8 code pages).
- `NO_COLOR` is honored: states are still readable through their symbols and words.
- The dashboard needs at least 80 x 24; below that it shows a single "enlarge the terminal" message.
