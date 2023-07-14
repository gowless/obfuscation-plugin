package xyz.xenondevs.vetric.transformer.impl.shrinking

import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodNode
import xyz.xenondevs.bytebase.jvm.JavaArchive
import xyz.xenondevs.vetric.transformer.ClassTransformer
import xyz.xenondevs.vetric.transformer.TransformerPriority
import xyz.xenondevs.vetric.utils.pluralize

class LineNumberRemover : ClassTransformer("LineNumberRemover", TransformerPriority.LOWEST) {
    
    private var totalCounter = 0
    
    override fun transform(jar: JavaArchive) {
        super.transform(jar)
        logger.info("Removed a total of $totalCounter line numbers")
        totalCounter = 0
    }
    
    override fun transformMethod(method: MethodNode) {
        val before = method.instructions.size()
        method.instructions.removeAll { it is LineNumberNode }
        val count = before - method.instructions.size()
        logger.debug("Removed %d line %s from %s", count, "number".pluralize(count), getFullName(method))
        totalCounter += count
    }
    
}
