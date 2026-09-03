# Andy design system

Andy supports light, dark, and tinted surface modes. Light and dark use the supplied neutral and
accent token family; tinted remains the user-selected color wash. The project chat rail and
transcript use the same structure in every mode so changing appearance never changes the
interaction model.

## Light mode

The light system is a paper canvas with bone wells, dark ink, quiet hairlines, modest system sans
typography, and a dark primary action. The established Airtable-blue tint remains the default
interactive accent; the supplied accent colors are available for status and provider states.
There are no gradients, neon fills, or decorative chrome.

### Color tokens

| Token | Value | Use |
| --- | --- | --- |
| background / void | `#181818` | deepest window and dark primary canvas |
| tertiary / carbon | `#1f1f1f` | dark project rail, header wells, and context bar shape |
| obsidian | `#181818` | dark transcript canvas and pane surface |
| surface / graphite | `#2b2b2b` | dark composer, cards, and user turns |
| smoke | `#383b3f` | dark selected rows and hairlines |
| ash | `#62666d` | borders, disabled text, and muted controls |
| fog | `#8a8f98` | tertiary text and metadata |
| mist | `#d0d6e0` | secondary text on dark surfaces |
| bone | `#e5e5e6` | light rail and light hover wells |
| paper | `#ffffff` | light canvas and primary light text |
| acid-lime | `#e4f222` | warnings and available accent |
| pulse-green | `#27a644` | success and additions |
| coral-red | `#eb5757` | errors and removals |
| signal-teal | `#02b8cc` | available secondary accent |
| iris-violet | `#6366f1` | violet provider/model accent |
| lavender | `#8b5cf6` | purple provider/model accent |

### Type and geometry

- Primary face: Haas Grotesk substitute, `Inter, system-ui, sans-serif`; weights 400 and 500.
- Supporting face: the same system sans; mono remains reserved for paths, commands, and raw data.
- Display scale: 48 / 40 / 32 px. Product titles: 24 / 20 / 18 px. Body and labels: 16 / 14 px.
- Line heights: 1.1 for display, 1.2–1.4 for titles and labels, 1.5 for body prose.
- Spacing: 4 / 8 / 12 / 16 / 24 / 32 / 48 px. Section rhythm is 96 px.
- Radius: 2 / 6 / 10 / 12 px. Pill controls use a fully rounded shape.
- Primary buttons are dark, rounded, and padded; secondary buttons are white with a quiet outline.

## Project page screenshot extraction

The two supplied references define the project chat rail and transcript composition. Their dark
appearance is treated as the dark-mode rendering of the same tokens above.

### Chat rail

- Rail width: 252 px at the reference viewport; keep it resizable and preserve the saved width.
- Rail surface: a slightly lifted tonal surface with one vertical hairline at the content edge.
- Header: a small, regular `Repositories` label at the top-left with compact filter/search and
  new-chat actions at the top-right. Do not show a large title or an always-open search field.
- Repository rows: 13–14 px regular labels, 16 px folder/home glyphs, 8–10 px horizontal inset.
- Chat rows: 12–13 px regular text, indented beneath the repository, single-line ellipsis, and a
  quiet right-aligned relative age (`2d`, `1mo`, etc.). Provider/status glyphs remain secondary.
- Selection: one full-width rounded row fill (`6–8 px` radius), with primary ink and no extra card.
- Row rhythm: approximately 32 px for repositories and 31–32 px for chat rows; avoid nested cards.
- Keep collapse, unread, priority, archive, show-more, context menu, delete, and new-chat actions.

### Transcript

- Center the transcript column in the available project content area. Use a readable prose width
  (about 800 px including the list's breathing room) rather than stretching to the pane edges.
- Keep approximately 10–18 px of inner horizontal breathing room. The reference user prompt spans
  nearly the whole centered column; assistant prose starts slightly inset within that same column.
- User turns are quiet tonal surfaces with a 10–12 px radius and 12 px internal padding. They are
  not right-floating speech bubbles and do not need an outline.
- Assistant turns are open text on the canvas. Use 14 px body type, approximately 20–21 px
  leading, and 14–16 px paragraph separation.
- Turn metadata (`Worked for…`, timestamps, copy/reply controls) is muted and sits close to the
  associated turn. It must not create a second card around assistant prose.
- Code and inline code use a restrained tonal block and the documented link/ink roles; do not add
  a new accent solely for transcript decoration.
- Keep selection, markdown links, file previews, tool expansion, permission requests, follow-live,
  transcript restoration, and composer/follow-up behavior unchanged.

## Dark mode

Dark mode uses the same restrained editorial relationships against the supplied neutral scale:

- Window and deepest canvas: `#181818` (background / void).
- Project rail and header wells: `#1f1f1f` (tertiary / carbon).
- Transcript content: `#181818` (obsidian).
- Composer, cards, and user turns: `#2b2b2b` (surface / graphite), with `#383b3f` (smoke) for
  selected rows and hairlines.
- Primary text: `#ffffff` (paper); supporting text uses `#d0d6e0` (mist) and `#8a8f98` (fog).
- Strong borders and disabled controls use `#62666d` (ash). No blue overlay or extra composer
  border is added to create separation.

Tinted mode continues to derive its quiet surfaces from the selected tint. The project rail and
transcript use the same geometry in all modes while mapping fills and ink to the active palette.

## Validation

Visual changes are checked with the desktop Compose UI tests and macOS Roborazzi captures. Only
intentional macOS baseline changes belong under `src/screenshotTest/roborazzi/macos/`.
