/*
 * CubeCut - cuts a speedcubing session video into one clip per solve.
 * Copyright (C) 2026 DadCuber
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, version 3 of the License,
 * with the additional terms in NOTICE.md (GPL section 7): the author credit must stay in the app's
 * About window, and changed versions must be marked as changed.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program (the
 * LICENSE file). If not, see <https://www.gnu.org/licenses/>.
 */
package com.example.cubecut

// ---------------------------------------------------------------------------------------
// (3.2-B) Puzzle and type tags: what was solved (3x3, Megaminx...) and how (OH, relay...). Pure logic, no Android imports.
// A video has a tag ("" = not tagged). A clip follows its video's tag unless the clip has its own:
//   ""      = follows the video
//   NO_TAG  = not tagged, even if the video is (the editor's "not this puzzle" untick)
//   a name  = its own tag (set in the Gallery)
// A name from "Other" is stored as typed (cleaned by cleanTag), so any puzzle fits without a schema change.
// ---------------------------------------------------------------------------------------

/** The puzzles offered as buttons, in this order (WCA puzzles plus FTO). Anything else is "Other" with a typed name. */
val PUZZLES = listOf("3x3", "2x2", "4x4", "5x5", "6x6", "7x7", "Megaminx", "Pyraminx", "Skewb", "Square-1", "Clock", "FTO")

/** The types offered as buttons. Anything else is "Other" with a typed name. */
val CATEGORIES = listOf("Normal", "OH", "BLD", "Relay", "PLL attack")

/** The plain type: left out of labels and file names, so a normal 3x3 solve is just "3x3". */
const val NORMAL_CATEGORY = "Normal"

/** (4.0-F) Where a video was filmed (owner, 2026-11-26). Fixed choices; "Other" is one of them, not a typed name. */
val PLACES = listOf("Home", "Competition", "Other")

/**
 * (4.0-F) Every kind of tag, in the order the tag window shows them. Each says:
 * - [label]: its name in the app;
 * - [clipMayDiffer]: a clip can have its own (else only the video has one; the clip follows it);
 * - [askOnImport]: asked when one video is imported (several videos ask nothing, owner 2026-11-26);
 * - [typedNames]: names can be typed (Other / New); else only the fixed [fixed] choices.
 * The choices are [fixed], and for typed kinds also the names already used ([TagKind.CUBE]: your own list of cube models, typed once,
 * then picked). A new kind is one entry here, plus its columns in SessionStore (footage, and cuts if [clipMayDiffer]), Backup, the
 * Gallery filter and [withUserFieldsOf].
 */
enum class TagKind(val label: String, val fixed: List<String>, val clipMayDiffer: Boolean, val askOnImport: Boolean, val typedNames: Boolean) {
    PUZZLE("Puzzle", PUZZLES, true, true, true),
    TYPE("Solve type", CATEGORIES, true, true, true),          // "Type" alone was unclear (owner, 2026-11-27)
    CUBE("Cube", emptyList(), true, true, true),
    PLACE("Place", PLACES, false, true, false);

    companion object {
        /** The kinds a clip's own tag window shows. */
        val forClips: List<TagKind> get() = entries.filter { it.clipMayDiffer }
    }
}

/**
 * A clip's own tag that means "not tagged, even though the video is". Never shown, never typed (cleanTag turns a typed "-" into "").
 * Stored in `cuts.puzzle` / `cuts.category` only.
 */
const val NO_TAG = "-"

/**
 * (4.0) The line at the top of a clip's tag window: the place is the video's only, so it isn't there (the owner looked for it, 2026-11-27).
 */
const val CLIP_PLACE_NOTE = "Place is set per session: Sessions tab → Set tags."

/** An "Other" name is cut to this many characters: long enough for "Mirror blocks 3x3", short enough for a file name. */
private const val MAX_TAG_LENGTH = 24

/** The tag a clip counts as: its own ([own]) or else its video's ([video]); NO_TAG gives "" (not tagged). */
fun resolveTag(own: String, video: String): String = when (own) {
    "" -> video
    NO_TAG -> ""
    else -> own
}

/**
 * A typed "Other" name made ready to store: one space between words, at most MAX_TAG_LENGTH characters. A name that is one of
 * [known] in other capitals becomes that entry ("oh" -> "OH"), so the filter doesn't split one tag in two. "-" (NO_TAG) gives "".
 */
fun cleanTag(text: String, known: List<String>): String {
    val t = text.replace(Regex("\\s+"), " ").trim().take(MAX_TAG_LENGTH).trim()
    if (t == NO_TAG) return ""
    return known.firstOrNull { it.equals(t, ignoreCase = true) } ?: t
}

/**
 * How a pair of resolved tags reads: "3x3 OH", "3x3" (Normal is left out), "Megaminx", "OH" (no puzzle), "" (not tagged).
 * Only a type of Normal with no puzzle shows as "Normal", so a tag never looks like nothing.
 */
fun tagLabel(puzzle: String, category: String): String {
    val shownCategory = if (category == NORMAL_CATEGORY && puzzle.isNotEmpty()) "" else category
    return listOf(puzzle, shownCategory).filter { it.isNotEmpty() }.joinToString(" ")
}

/** The tags in use, in a fixed order: the [known] buttons first in their order, then typed names A-Z. "" (not tagged) is left out. */
fun orderTags(values: Collection<String>, known: List<String>): List<String> {
    val distinct = values.filter { it.isNotEmpty() }.toSet()
    return known.filter { it in distinct } + distinct.filter { it !in known }.sortedBy { it.lowercase() }
}

/** True if a clip whose resolved tag is [value] passes a tag filter set to [filter] (null = any tag, "" = only clips not tagged). */
fun tagMatches(value: String, filter: String?): Boolean = filter == null || value == filter

/**
 * The clip's own tag after a tag window's choice (3.2-B): [choice] null = leave it as it is ([own]); otherwise the choice as stored
 * ("" = follow the video, NO_TAG = not tagged, or a name).
 */
fun applyTagChoice(own: String, choice: String?): String = choice ?: own

/**
 * A clip's own tag as stored: one that comes out the same as its video's tag ([video]) is stored as "" (follows the video), so a 3x3
 * clip in a 3x3 video is never told apart from the video's other clips, and follows the video if the video's tag changes later.
 * Also turns NO_TAG into "" in a video that isn't tagged.
 */
fun normalizeClipTag(own: String, video: String): String = if (resolveTag(own, video) == video) "" else own

/** True if a clip with own tags ([ownPuzzle], [ownCategory]) shows the same as its video's tags. */
fun followsVideoTags(ownPuzzle: String, ownCategory: String, videoPuzzle: String, videoCategory: String): Boolean =
    normalizeClipTag(ownPuzzle, videoPuzzle) == "" && normalizeClipTag(ownCategory, videoCategory) == ""

/**
 * The video's tag when a tag window's choice covers all of its clips: "" (follow) and null keep [video] as it is, NO_TAG makes it
 * not tagged, a name becomes the video's tag. Its clips then follow it (see resetOwnTag).
 */
fun videoTagFor(video: String, choice: String?): String = when (choice) {
    null, "" -> video
    NO_TAG -> ""
    else -> choice
}

/** A clip's own tag when its whole video took the choice (videoTagFor): it follows the video again, unless the choice left it as it was. */
fun resetOwnTag(own: String, choice: String?): String = if (choice == null) own else ""
