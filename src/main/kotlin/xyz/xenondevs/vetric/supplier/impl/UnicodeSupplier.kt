package xyz.xenondevs.vetric.supplier.impl

import xyz.xenondevs.vetric.supplier.StringSupplier
import kotlin.random.Random

class UnicodeSupplier(private val min: Int, private val max: Int) : StringSupplier("Unicode") {
    
    constructor(defaultLength: Int = 20) : this(defaultLength, defaultLength)
    
    override fun randomString(): String {
        val length = Random.nextInt(min, max + 1)
        return buildString {
            while (this.length < length) {
                val char = Random.nextInt(Character.MAX_CODE_POINT).toChar()
                if (char.isJavaIdentifierPart())
                    append(char)
            }
        }
    }
    
}