// Copyright 2026 The ThunderID Authors
// SPDX-License-Identifier: Apache-2.0

package dev.thunderid.compose.i18n

import dev.thunderid.android.FlowErrorText

/**
 * Resolves `{{ t(key) }}` and `{{ meta(path) }}` template literals embedded in server-returned
 * component labels and placeholders.
 *
 * `{{ t(signin:forms.credentials.title) }}` is resolved via the i18n translations returned by
 * `GET /flow/meta`. Everything after the colon is used as a single lookup key into the
 * namespace's translation map (not a further nested dot-walk).
 *
 * `{{ meta(application.name) }}` is resolved via a dot-path lookup on the flow meta map returned
 * by `GET /flow/meta`.
 *
 * Any unrecognized expression is left unchanged.
 */
class FlowTemplateResolver(
    private val meta: Map<String, Any?>,
) {
    companion object {
        private val TEMPLATE_REGEX = Regex("\\{\\{\\s*(.*?)\\s*\\}\\}")
        private val PARAM_REGEX = Regex("\\{\\{\\s*param\\(\\s*(\\w+)\\s*\\)\\s*\\}\\}")
    }

    /**
     * The translation of a flow error's `key`, with its `params` substituted, or null when there is
     * none to show. As in the JavaScript SDK, the key is looked up as `<namespace>.<key>` and then
     * under the `system` namespace, and a translation that still has a placeholder is not used.
     */
    fun translate(text: FlowErrorText?): String? {
        val key = text?.key?.takeIf { it.isNotBlank() } ?: return null
        return listOf(key, "system.$key").firstNotNullOfOrNull { candidate ->
            val dot = candidate.indexOf('.')
            if (dot == -1) return@firstNotNullOfOrNull null
            val translation =
                resolveTranslation("${candidate.substring(0, dot)}:${candidate.substring(dot + 1)}")
                    .replace(PARAM_REGEX) { text.params?.get(it.groupValues[1]) ?: it.value }
            translation.takeIf { it.isNotEmpty() && !PARAM_REGEX.containsMatchIn(it) }
        }
    }

    fun resolve(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        if (!text.contains("{{")) return text
        return TEMPLATE_REGEX.replace(text) { match ->
            replace(match)
        }
    }

    private fun replace(match: MatchResult): String {
        val content = match.groupValues[1].trim()

        if (content.startsWith("t(") && content.endsWith(")")) {
            return resolveTranslation(content.substring(2, content.length - 1))
        }

        if (content.startsWith("meta(") && content.endsWith(")")) {
            return resolveMeta(content.substring(5, content.length - 1))
        }

        return match.value
    }

    private fun resolveTranslation(key: String): String {
        // key: "signin:forms.credentials.title" -> namespace="signin", dotKey="forms.credentials.title"
        val colonIdx = key.indexOf(':')
        if (colonIdx == -1) return ""

        val namespace = key.substring(0, colonIdx)
        val dotKey = key.substring(colonIdx + 1)

        val translations = (meta["i18n"] as? Map<*, *>)?.get("translations") as? Map<*, *> ?: return ""
        val nsMap = translations[namespace] as? Map<*, *> ?: return ""
        val value = nsMap[dotKey]
        return if (value is String) value else ""
    }

    private fun resolveMeta(path: String): String {
        // path: "application.logoUrl" -> dot-path lookup on meta
        var current: Any? = meta
        for (part in path.split(".")) {
            current =
                if (current is Map<*, *>) {
                    current[part]
                } else {
                    return ""
                }
        }
        return when (current) {
            is String -> current
            null -> ""
            else -> current.toString()
        }
    }
}
