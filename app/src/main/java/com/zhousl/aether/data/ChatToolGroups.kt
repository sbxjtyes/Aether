package com.zhousl.aether.data

object ChatToolGroups {
    const val FilesImages = "files_images"
    const val Terminal = "terminal"
    const val Web = "web"
    const val Extensions = "extensions"

    val All: List<String> = listOf(
        FilesImages,
        Terminal,
        Web,
        Extensions,
    )

    val DefaultEnabled: List<String> = All
}

fun normalizeChatToolGroups(groups: List<String>): List<String> {
    val known = ChatToolGroups.All.toSet()
    return groups
        .map(String::trim)
        .filter(known::contains)
        .distinct()
}

fun chatToolGroupsFromStored(groups: List<String>?): List<String> =
    groups?.let(::normalizeChatToolGroups) ?: ChatToolGroups.DefaultEnabled

fun isChatToolGroupEnabled(
    enabledToolGroups: List<String>,
    group: String,
): Boolean = group in enabledToolGroups
