package app.andy.terminal.rust

import app.andy.model.TerminalAppearanceSnapshot
import app.andy.model.palette

/** Packs Andy Settings palette for [RustTerminalEngine.setPalette]. */
fun TerminalAppearanceSnapshot.toRustPaletteArgb(): IntArray = palette().toEngineArgb()

fun TerminalAppearanceSnapshot.selectionArgb(): Int = palette().selection

fun TerminalAppearanceSnapshot.selectionTextArgb(): Int = palette().selectionText
