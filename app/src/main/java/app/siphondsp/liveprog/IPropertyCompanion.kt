package app.siphondsp.liveprog

interface IPropertyCompanion {
    val definitionRegex: Regex
    fun parse(line: String, contents: String, groupIndex: Int): EelBaseProperty?
}