package com.zhousl.aether.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle

/**
 * 轻量级代码高亮器（语法着色）。
 *
 * 设计目标：不引入第三方依赖、与语言无关地覆盖主流语言（Kotlin/Java/JS/TS/Python/Go/C 等）的
 * 常见词法元素：注释、字符串、数字、关键字、标点。它不是完整的解析器，仅做基于扫描的着色，
 * 以保证流式渲染时的性能与稳定性。
 */
internal data class CodeHighlightColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val punctuation: Color,
    val plain: Color,
)

/** 跨语言的常见关键字集合，避免为每种语言单独维护词表。 */
private val CommonCodeKeywords: Set<String> = setOf(
    // 通用控制流
    "if", "else", "elif", "for", "while", "do", "switch", "case", "default", "break",
    "continue", "return", "goto", "yield", "await", "async", "when", "match",
    // 声明与类型
    "fun", "func", "function", "def", "class", "interface", "enum", "struct", "trait",
    "object", "val", "var", "let", "const", "static", "final", "abstract", "open", "sealed",
    "data", "public", "private", "protected", "internal", "package", "import", "from",
    "namespace", "using", "module", "export", "extends", "implements", "override", "virtual",
    "void", "int", "long", "short", "float", "double", "bool", "boolean", "char", "byte",
    "string", "str", "list", "dict", "map", "set", "var", "auto", "type", "typedef",
    // 值与异常
    "true", "false", "null", "nil", "none", "None", "True", "False", "undefined", "this",
    "self", "super", "new", "delete", "try", "catch", "except", "finally", "throw", "throws",
    "raise", "with", "as", "in", "is", "not", "and", "or", "lambda", "pass", "global",
    "defer", "go", "chan", "select", "suspend", "companion", "init", "constructor",
)

/** 简体中文/英文界面下的语言展示名（用于代码块标题）。 */
internal fun codeFenceLanguageLabel(language: String): String {
    val normalized = language.trim().lowercase()
    if (normalized.isBlank()) return ""
    return when (normalized) {
        "kt", "kotlin" -> "Kotlin"
        "java" -> "Java"
        "js", "javascript" -> "JavaScript"
        "ts", "typescript" -> "TypeScript"
        "tsx" -> "TSX"
        "jsx" -> "JSX"
        "py", "python" -> "Python"
        "go", "golang" -> "Go"
        "rs", "rust" -> "Rust"
        "c" -> "C"
        "cpp", "c++", "cc", "cxx" -> "C++"
        "cs", "csharp" -> "C#"
        "sh", "bash", "shell", "zsh" -> "Shell"
        "json" -> "JSON"
        "yaml", "yml" -> "YAML"
        "xml" -> "XML"
        "html" -> "HTML"
        "css" -> "CSS"
        "sql" -> "SQL"
        "md", "markdown" -> "Markdown"
        "php" -> "PHP"
        "rb", "ruby" -> "Ruby"
        "swift" -> "Swift"
        "dart" -> "Dart"
        "toml" -> "TOML"
        "dockerfile" -> "Dockerfile"
        else -> normalized.replaceFirstChar { it.uppercaseChar() }
    }
}

/** 注释/字符串等词法元素采用 # 前缀语言时使用井号注释。 */
private fun usesHashComment(language: String): Boolean {
    return when (language.trim().lowercase()) {
        "py", "python", "sh", "bash", "shell", "zsh", "yaml", "yml", "rb", "ruby",
        "toml", "dockerfile", "r", "perl", "pl" -> true
        else -> false
    }
}

/**
 * 对源码进行词法着色，返回带颜色的 [AnnotatedString]。
 *
 * 扫描顺序保证正确性：先识别注释与字符串（它们内部不再着色），再识别数字与标识符，
 * 标识符命中关键字表则按关键字着色。
 */
internal fun highlightCode(
    code: String,
    language: String,
    colors: CodeHighlightColors,
): AnnotatedString = buildAnnotatedString {
    if (code.isEmpty()) return@buildAnnotatedString

    val hashComment = usesHashComment(language)
    var i = 0
    val n = code.length

    fun appendStyled(start: Int, end: Int, color: Color, italic: Boolean = false) {
        if (end <= start) return
        pushStyle(
            SpanStyle(
                color = color,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            )
        )
        append(code.substring(start, end))
        pop()
    }

    while (i < n) {
        val c = code[i]

        // 行注释：// 或 #（按语言）
        if (c == '/' && i + 1 < n && code[i + 1] == '/') {
            val start = i
            while (i < n && code[i] != '\n') i++
            appendStyled(start, i, colors.comment, italic = true)
            continue
        }
        if (hashComment && c == '#') {
            val start = i
            while (i < n && code[i] != '\n') i++
            appendStyled(start, i, colors.comment, italic = true)
            continue
        }

        // 块注释：/* ... */
        if (c == '/' && i + 1 < n && code[i + 1] == '*') {
            val start = i
            i += 2
            while (i < n && !(code[i] == '*' && i + 1 < n && code[i + 1] == '/')) i++
            if (i < n) i += 2
            appendStyled(start, i, colors.comment, italic = true)
            continue
        }

        // 字符串：" ' ` 三种引号，支持转义
        if (c == '"' || c == '\'' || c == '`') {
            val quote = c
            val start = i
            i++
            while (i < n) {
                if (code[i] == '\\' && i + 1 < n) {
                    i += 2
                    continue
                }
                if (code[i] == quote) {
                    i++
                    break
                }
                // 普通引号不跨多行（反引号允许跨行，常见于模板字符串）
                if (code[i] == '\n' && quote != '`') break
                i++
            }
            appendStyled(start, i, colors.string)
            continue
        }

        // 数字：整数/小数/十六进制
        if (c.isDigit()) {
            val start = i
            while (i < n && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '_')) i++
            appendStyled(start, i, colors.number)
            continue
        }

        // 标识符 / 关键字
        if (c.isLetter() || c == '_' || c == '$' || c == '@') {
            val start = i
            while (i < n && (code[i].isLetterOrDigit() || code[i] == '_' || code[i] == '$')) i++
            val word = code.substring(start, i)
            if (word in CommonCodeKeywords) {
                appendStyled(start, i, colors.keyword)
            } else {
                appendStyled(start, i, colors.plain)
            }
            continue
        }

        // 标点符号
        if (!c.isWhitespace() && (c in "{}()[];:,.<>=+-*/%&|!?~^")) {
            appendStyled(i, i + 1, colors.punctuation)
            i++
            continue
        }

        // 其它字符（空白等）原样输出
        appendStyled(i, i + 1, colors.plain)
        i++
    }
}
