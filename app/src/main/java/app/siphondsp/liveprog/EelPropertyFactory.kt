package app.siphondsp.liveprog

import kotlin.reflect.full.companionObjectInstance

object EelPropertyFactory {
    private val typeOrder = arrayOf(
        EelLabelProperty::class.companionObjectInstance,
        EelNumberRangeProperty::class.companionObjectInstance,
        EelListProperty::class.companionObjectInstance
    ).map {
        it as IPropertyCompanion
    }

    fun create(line: String, contents: String, groupIndex: Int): EelBaseProperty? {
        typeOrder.forEach { factory ->
            factory.parse(line, contents, groupIndex)?.let { return it }
        }
        return null
    }
}