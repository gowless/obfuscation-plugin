package xyz.xenondevs.vetric.transformer.impl.obfuscation.string.pool

import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode
import xyz.xenondevs.bytebase.asm.buildInsnList
import xyz.xenondevs.bytebase.jvm.ClassWrapper
import xyz.xenondevs.bytebase.util.replace
import xyz.xenondevs.vetric.supplier.DEFAULT_SUPPLIER
import xyz.xenondevs.vetric.transformer.TransformerPriority
import xyz.xenondevs.vetric.transformer.impl.obfuscation.string.StringTransformer
import xyz.xenondevs.vetric.utils.filterTypeSub
import xyz.xenondevs.vetric.utils.getStringPool

/**
 * Combines all strings into a single one and gets strings using [String#substring].
 *
 * ## Overlapping Strings
 * This transformer also automatically combines similar strings. For example, if you have the following strings:
 * ```
 * "hello"
 * "locale"
 * "cache"
 * "hell"
 * ```
 * Then the transformer will combine them into one string: ``"cachellocale"``.
 * ```
 * "hello" = 3..7
 * "locale" = 6..11
 * "cache" = 0..4
 * "hell" = 3..6
 * ```
 * ## useArrayPool
 * If useArrayPool is set to true, the transformer will perform all substring operations in ``<clinit>`` and store
 * the result in an array. This speeds up the process of getting strings by a lot but is vulnerable to agent attacks.
 *
 * ## extraShuffle
 * If extraShuffle is set to true, the transformer will fill the array in a random order. This can be used to avoid
 * decompilers from identifying array initialization as a pattern. (only works if useArrayPool is set to true as well)
 */
class SingleStringPooler : StringTransformer("SingleStringPooler", TransformerPriority.LOWEST) {
    
    var useArrayPool = true
    var extraShuffle = true
    val supplier = DEFAULT_SUPPLIER
    
    override fun transform(clazz: ClassWrapper) {
        val strings = clazz.getStringPool()
        if (strings.isEmpty()) return
        
        if (useArrayPool) poolStringsArray(clazz, strings)
        else poolStringsSubstring(clazz, strings)
    }
    
    private fun poolStringsArray(clazz: ClassWrapper, strings: Set<String>) {
        val supp = supplier.create(2)
        val overlapped = overlapStrings(strings)
        val lookupTable = generateIndices(overlapped, strings)
        val arrayIndices = HashMap<String, Int>()
        val poolField = FieldNode(ACC_PUBLIC or ACC_STATIC, supp.randomString(), "[Ljava/lang/String;", null, null)
        val poolFieldInit = MethodNode(ACC_PUBLIC or ACC_STATIC, supp.randomString(), "()[Ljava/lang/String;", null, null)
        clazz.fields.add(poolField)
        clazz.methods.add(poolFieldInit)
        poolFieldInit.instructions = buildInsnList {
            ldc(overlapped)
            aStore(1)
            ldc(strings.size)
            aNewArray("java/lang/String")
            var sequence = strings.asSequence().withIndex()
            if (extraShuffle) sequence = sequence.shuffled()
            sequence.forEach {
                val string = it.value
                val index = it.index
                val start = lookupTable[string]!!
                arrayIndices[string] = index
                dup()
                ldc(index)
                if (extraShuffle) {
                    ldc(0)
                    ior()
                }
                aLoad(1)
                ldc(start)
                ldc(start + string.length)
                invokeVirtual("java/lang/String", "substring", "(II)Ljava/lang/String;")
                aastore()
            }
            areturn()
        }
        clazz.getOrCreateClassInit().instructions.insert(buildInsnList {
            invokeStatic(clazz.name, poolFieldInit.name, "()[Ljava/lang/String;")
            putStatic(clazz.name, poolField.name, "[Ljava/lang/String;")
        })
        StringPooler.replaceStrings(clazz, arrayIndices, poolField, poolFieldInit)
    }
    
    private fun poolStringsSubstring(clazz: ClassWrapper, strings: Set<String>) {
        val supp = supplier.create(1)
        val overlapped = overlapStrings(strings)
        val lookupTable = generateIndices(overlapped, strings) // String -> start
        val pool = FieldNode(ACC_PUBLIC or ACC_STATIC, supp.randomString(), "Ljava/lang/String;", null, overlapped)
        
        clazz.fields.add(pool)
        clazz.methods.forEach {
            val insnList = it.instructions
            insnList.filterTypeSub<LdcInsnNode, String>(LdcInsnNode::cst).forEach { insn ->
                val value = insn.cst as String
                val start = lookupTable[value]!!
                insnList.replace(insn, buildInsnList {
                    getStatic(clazz.name, pool.name, "Ljava/lang/String;")
                    ldc(start)
                    ldc(start + value.length)
                    invokeVirtual("java/lang/String", "substring", "(II)Ljava/lang/String;")
                })
            }
        }
    }
    
    private fun overlapStrings(set: Set<String>): String {
        val sorted = set.sortedByDescending(String::length)
        var string = ""
        sorted.forEach {
            if (it.isNotEmpty() && !string.contains(it)) {
                string = if (string.isEmpty()) it else overlapStrings(string, it)
            }
        }
        return string
    }
    
    private fun overlapStrings(string1: String, string2: String): String {
        val (longString, shortString) = sortStrings(string1, string2) // Might not be needed?
        
        var startIndex = longString.lastIndexOf(shortString[0])
        var length1 = findDifference(longString, shortString, startIndex)
        if (length1 == longString.length)
            return longString.take(startIndex) + shortString
        
        startIndex = shortString.lastIndexOf(longString[0])
        length1 = findDifference(shortString, longString, startIndex)
        if (length1 == shortString.length)
            return shortString + longString.drop(shortString.length - startIndex)
        
        return shortString + longString
    }
    
    private fun findDifference(string1: String, string2: String, startIndex: Int): Int {
        val length1 = string1.length
        val length2 = string2.length
        var index1 = startIndex
        var index2 = 0
        if (index1 != -1) {
            while (index1 < length1 && index2 < length2 && string1[index1] == string2[index2]) {
                ++index1
                ++index2
            }
        }
        return index1
    }
    
    private fun sortStrings(string1: String, string2: String): Pair<String, String> =
        if (string1.length > string2.length) string1 to string2 else string2 to string1
    
    private fun generateIndices(overlapped: String, strings: Set<String>): HashMap<String, Int> {
        val indices = hashMapOf<String, Int>()
        strings.forEach {
            val index = overlapped.indexOf(it)
            if (index == -1)
                throw IllegalStateException()
            indices[it] = index
        }
        return indices
    }
}
