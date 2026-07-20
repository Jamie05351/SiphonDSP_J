package app.siphondsp.liveprog

/**
 * Represents a commented-out slider/list declaration (`//key:default<min,max,step{opts}>Label`)
 * used purely as a static section header, a convention some community scripts use for visual
 * grouping. Rendered as a bold, non-interactive header rather than a real control. Any bracketed
 * `{opts}` content is kept as [summary] so a single line can still carry a second decorative line.
 */
class EelLabelProperty(
    key: String,
    description: String,
    val summary: String = "",
) : EelBaseProperty(key, description) {
    override fun hasDefault() = false
    override fun isDefault() = true
    override fun restoreDefaults() {}
    override fun valueAsString() = ""
    override fun manipulateProperty(contents: String): String? = contents

    override fun toString(): String {
        return "key=$key; desc=$description; summary=$summary (label)"
    }

    companion object : IPropertyCompanion {
        override val definitionRegex =
            """^\s*//\s*(?<var>\w+):(?<def>-?\d+\.?\d*)?<(?<min>-?\d+\.?\d*),(?<max>-?\d+\.?\d*),?(?<step>-?\d+\.?\d*)?(?<opt>\{[^}]*\})?>(?<desc>[\s\S][^\n]*)""".toRegex()

        override fun parse(line: String, contents: String): EelBaseProperty? {
            val match = definitionRegex.find(line) ?: return null
            val groups = match.groups

            val key = groups[1]?.value ?: return null
            val desc = groups[7]?.value?.trim() ?: return null
            val summary = groups[6]?.value?.trim('{', '}')?.trim() ?: ""

            return EelLabelProperty(key, desc, summary)
        }
    }
}
