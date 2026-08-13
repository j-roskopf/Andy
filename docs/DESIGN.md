# Design System: Andy

## 1. Visual Theme & Atmosphere

A soft, minimal developer workspace with gallery-airy density and fluid motion. The atmosphere is calm and focused — deep charcoal surfaces, generous negative space, and a single accent used as punctuation rather than decoration. Inspired by modern AI-native tools: rounded containers, borderless hierarchy, and tonal separation instead of hard lines.

- **Density:** 4 — balanced for daily developer workflows without cockpit clutter
- **Variance:** 5 — left-aligned navigation with centered content columns where appropriate
- **Motion:** 6 — spring-weighted transitions, staggered reveals, perpetual micro-loops on active indicators

## 2. Color Palette & Roles

### Dark (default)

- **Void Canvas** (`#0A0A0A`) — Primary window and content background
- **Sidebar Veil** (`#111111`) — Navigation rail, slightly lifted from canvas
- **Pane Surface** (`#0F0F0F`) — Secondary panels, sidebars within screens
- **Raised Surface** (`#171717`) — Cards, popovers, elevated containers
- **Hover Wash** (`#1A1A1A`) — Interactive hover states
- **Selected Fill** (`#1F1F1F`) — Active navigation rows, selected list items
- **Ink Primary** (white @ 92%) — Headlines, primary labels
- **Ink Secondary** (white @ 68%) — Descriptions, metadata
- **Ink Tertiary** (white @ 44%) — Timestamps, hints, disabled-adjacent text
- **Whisper Border** (white @ 5%) — Structural separators; prefer tonal shifts over borders
- **Accent** (user tint, saturation < 80%) — CTAs, active nav indicator, focus rings, cursor

### Light

- **Canvas** (`#F2F2F2`) — Window and main content background (recessed base)
- **Sidebar Mist** (`#E8E8E8`) — Navigation rail
- **Pane / Well** (`#EBEBEB`) — Secondary panels, kanban lanes, list wells
- **Raised White** (`#FFFFFF`) — Cards, composer, elevated chrome
- **Ink Primary** (black @ 88%) — Primary text
- **Whisper Border** (black @ 8%) — Subtle structural lines

Max 1 accent color. No purple/blue neon gradients. No pure black (`#000000`).

## 3. Typography Rules

- **Display:** System sans-serif — track-tight headlines, weight-driven hierarchy
- **Body:** System sans-serif — relaxed 1.45 leading, 65ch max-width in prose areas
- **Mono:** System monospace — paths, serials, log output, command input
- **Scale:** 13sp body, 12sp labels, 14sp section titles, 20–24sp display
- **Banned:** Inter (not available in Compose desktop anyway), generic serif in dashboards

## 4. Component Stylings

- **Buttons:** Pill-shaped (`999dp` radius) for primary actions; ghost/outline for secondary. Tactile `-1px` translate on press. No outer glows.
- **Cards:** Borderless by default — elevation through background color shift only. Generous 14–16dp corner radius. Borders reserved for focus/error states.
- **Inputs:** Floating pill containers, 32dp height, label above (never floating). Accent focus ring.
- **Filter pills:** Full pill shape, tonal fill when selected, no hard borders when unselected.
- **Navigation:** Active row gets a 3dp accent bar on the leading edge plus selected fill.
- **Loaders:** Skeletal shimmer matching layout dimensions. No circular spinners.
- **Empty states:** Composed center-aligned message with secondary color, no clipart.

## 5. Layout Principles

- CSS Grid / Compose `Row`+`Column` with fixed token spacing — no percentage flexbox math
- Max content width 760dp for chat/prose columns
- Sidebar 220dp expanded, 52dp collapsed
- Toolbar 52dp, controls 32dp tall
- Single-column collapse below 768dp equivalent (not applicable to desktop primary target)
- Separation via background tonal shifts, not borders

## 6. Motion & Interaction

- **Spring default:** stiffness 100, damping 20 — weighty, premium feel
- **Timing:** 100ms fast, 170ms standard, 240ms spatial
- **Sidebar collapse:** width + label fade orchestrated together
- **Hover:** background wash transition 140–200ms
- **Active press:** scale 0.98 or translateY 1px
- Animate `transform` and `opacity` only

## 7. Anti-Patterns (Banned)

- No emojis in UI chrome
- No pure black backgrounds
- No neon/outer glow shadows
- No oversaturated accent colors
- No 3-column equal feature grids
- No generic placeholder names ("John Doe", "Acme Corp")
- No AI copywriting clichés ("Elevate", "Seamless", "Unleash")
- No filler UI ("Scroll to explore", bouncing chevrons)
- No visible borders where tonal separation suffices
- No sharp 90° corners on interactive elements
