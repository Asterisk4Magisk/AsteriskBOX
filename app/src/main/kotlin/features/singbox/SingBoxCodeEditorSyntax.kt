// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.singbox

internal data class SingBoxCodeEditorBehavior(
    val readOnly: Boolean,
) {
    val editable: Boolean = !readOnly
    val enabled: Boolean = true
    val selectionEnabled: Boolean = true
}

internal enum class SingBoxCodeLanguage {
    Json,
}

internal enum class CodeLexState {
    Normal,
}

internal enum class CodeTokenKind {
    Normal,
    Keyword,
    Key,
    String,
    Number,
    Literal,
    Function,
    Comment,
    Operator,
}

internal data class CodeToken(
    val start: Int,
    val end: Int,
    val kind: CodeTokenKind,
)

internal data class CodeLineTokens(
    val line: String,
    val state: CodeLexState,
    val tokens: List<CodeToken>,
)

internal fun tokenizeCodeLine(
    line: CharSequence,
    language: SingBoxCodeLanguage,
    state: CodeLexState = CodeLexState.Normal,
): CodeLineTokens {
    check(language == SingBoxCodeLanguage.Json)
    check(state == CodeLexState.Normal)
    return tokenizeJsonLine(line.toString())
}

private fun tokenizeJsonLine(line: String): CodeLineTokens {
    val tokens = mutableListOf<CodeToken>()
    var index = 0
    while (index < line.length) {
        val start = index
        when {
            line[index] == '"' -> {
                index = line.quotedTokenEnd(index)
                val next = line.nextNonWhitespace(index)
                tokens += CodeToken(
                    start = start,
                    end = index,
                    kind = if (next == ':') CodeTokenKind.Key else CodeTokenKind.String,
                )
            }
            line[index].isDigit() ||
                (line[index] == '-' && line.getOrNull(index + 1)?.isDigit() == true) -> {
                index = line.jsonNumberEnd(index)
                tokens += CodeToken(start, index, CodeTokenKind.Number)
            }
            line[index].isLetter() -> {
                index += 1
                while (index < line.length && line[index].isLetter()) index += 1
                val value = line.substring(start, index)
                tokens += CodeToken(
                    start,
                    index,
                    if (value in JsonLiterals) CodeTokenKind.Literal else CodeTokenKind.Normal,
                )
            }
            line[index] in JsonOperators -> {
                index += 1
                tokens += CodeToken(start, index, CodeTokenKind.Operator)
            }
            else -> {
                index += 1
                while (index < line.length && !line.isJsonTokenStart(index)) index += 1
                tokens += CodeToken(start, index, CodeTokenKind.Normal)
            }
        }
    }
    if (tokens.isEmpty()) tokens += CodeToken(0, 0, CodeTokenKind.Normal)
    return CodeLineTokens(line, CodeLexState.Normal, tokens)
}

private fun String.quotedTokenEnd(start: Int): Int {
    var index = start + 1
    var escaped = false
    while (index < length) {
        val char = this[index]
        when {
            escaped -> escaped = false
            char == '\\' -> escaped = true
            char == '"' -> return index + 1
        }
        index += 1
    }
    return length
}

private fun String.jsonNumberEnd(start: Int): Int {
    var index = start + 1
    while (index < length && this[index] in JsonNumberCharacters) index += 1
    return index
}

private fun String.nextNonWhitespace(start: Int): Char? {
    var index = start
    while (index < length && this[index].isWhitespace()) index += 1
    return getOrNull(index)
}

private fun String.isJsonTokenStart(index: Int): Boolean {
    val char = this[index]
    return char == '"' ||
        char.isDigit() ||
        char.isLetter() ||
        char in JsonOperators ||
        (char == '-' && getOrNull(index + 1)?.isDigit() == true)
}

private val JsonOperators = setOf('[', ']', '{', '}', ',', ':')
private val JsonNumberCharacters = setOf('+', '-', '.', 'e', 'E') + ('0'..'9')
private val JsonLiterals = setOf("true", "false", "null")
