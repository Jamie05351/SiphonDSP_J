package app.siphondsp.compose.screens

/**
 * A `remember` key for a DSP config array that changes exactly when its contents do. Holds its
 * own copy, so a caller that mutates the array in place still gets a new key.
 *
 * Not `contentHashCode()`: that collides on everyday edits. Setting a Left/Right pair of crossover
 * slopes (13 slots apart) from LR4 to BW2 leaves it unchanged -- the two terms of the change are
 * 0x40000000 times an odd power of 31 at odd and even distances, and they cancel mod 2^32 -- so
 * a graph keyed on the hash never redrew for that change.
 */
internal class ValuesKey(values: FloatArray) {
    private val values = values.copyOf()

    override fun equals(other: Any?): Boolean = other is ValuesKey && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()
}
