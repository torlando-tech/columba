package network.columba.app.rns.backend.kt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.util.jar.JarFile

/**
 * Binary-compatibility guard for the pinned Kotlin protocol stack
 * (reticulum-kt + LXMF-kt + LXST-kt, see `gradle/libs.versions.toml`).
 *
 * Background (GitHub #1252 / #1272): the JVM links methods by owner, name
 * **and** descriptor. JitPack publishes each artifact compiled against one
 * exact `reticulum-kt` version, while Gradle puts the highest `rns-core` on
 * the runtime classpath. When an artifact was compiled against an older
 * `rns-core` whose signature later changed, the call site keeps the old
 * descriptor and the JVM aborts at first execution with
 * `java.lang.NoSuchMethodError`. That is exactly how the released APKs crashed
 * inside `LXMRouter.sendViaPropagation()` and `LXMRouter.sendViaLink()`; every
 * frame in the reported traces was LXMF-kt bytecode, never Columba code.
 *
 * The test reads every `Methodref`/`InterfaceMethodref` out of the pinned
 * protocol jars on the unit-test classpath whose owner is `network.reticulum.*`
 * and resolves it against the classes actually present. Red means the pins are
 * ABI-incompatible and the shipped stack throws `NoSuchMethodError`.
 */
class KtStackBinaryCompatTest {

    @Test
    fun `every reticulum reference from the pinned lxmf and lxst artifacts resolves`() {
        val artifacts = protocolArtifacts()
        assertTrue(
            "Expected the pinned protocol artifacts on the unit-test classpath, found none: " +
                (System.getProperty(RUNTIME_CLASSPATH_PROPERTY) ?: System.getProperty("java.class.path")),
            artifacts.isNotEmpty(),
        )

        val failures = mutableListOf<String>()
        for (jar in artifacts) {
            JarFile(jar).use { archive ->
                for (entry in archive.entries()) {
                    if (!entry.name.endsWith(".class") || entry.name.startsWith("META-INF/")) continue
                    val bytes = archive.getInputStream(entry).use { it.readBytes() }
                    failures += unresolvedReticulumReferences(bytes, "${entry.name} in ${jar.name}")
                }
            }
        }

        assertTrue(
            "Pinned protocol artifacts are ABI-incompatible with the resolved reticulum-kt classes, " +
                "so the shipped stack throws NoSuchMethodError at runtime:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /** The pinned Kotlin protocol jars the app ships, e.g. `LXMF-kt-v0.0.14.jar`. */
    private fun protocolArtifacts(): List<File> =
        // Prefer the classpath Gradle hands the test JVM: the worker's
        // `java.class.path` is not a reliable view of the test runtime classpath.
        (System.getProperty(RUNTIME_CLASSPATH_PROPERTY) ?: System.getProperty("java.class.path"))
            .orEmpty()
            .split(File.pathSeparator)
            .map(::File)
            .filter { it.isFile && it.name.endsWith(".jar") && PROTOCOL_ARTIFACTS.any(it.name::startsWith) }

    private fun unresolvedReticulumReferences(classBytes: ByteArray, origin: String): List<String> =
        parseMethodReferences(classBytes)
            .filter { it.owner.startsWith(RETICULUM_PREFIX) }
            .mapNotNull { reference ->
                val className = reference.owner.replace('/', '.')
                val resolution = runCatching {
                    val owner = Class.forName(className, false, javaClass.classLoader)
                    declareOrInheritedMethod(owner, reference)
                }
                val found = resolution.getOrElse { false }
                if (!found) {
                    "  NoSuchMethodError: No such method ${reference.name}${reference.descriptor} in class " +
                        "$className; referenced from $origin"
                } else {
                    null
                }
            }

    /**
     * JVM linkage finds a referenced method on the referenced class or any of its
     * supertypes, including non-public members declared by same-module code, and
     * matches the **full** descriptor: a dependency update that changes only a
     * return type still throws NoSuchMethodError.
     */
    private fun declareOrInheritedMethod(owner: Class<*>, reference: MethodReference): Boolean {
        if (reference.name == "<init>") {
            return owner.declaredConstructors.any { descriptorOf(it.parameterTypes, RETURN_VOID) == reference.descriptor }
        }
        val visited = HashSet<Class<*>>()
        val queue = ArrayDeque(listOf(owner))
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            if (!visited.add(type)) continue
            if (type.declaredMethods.any { it.name == reference.name && descriptorOf(it) == reference.descriptor }) {
                return true
            }
            type.superclass?.let(queue::add)
            queue.addAll(type.interfaces)
        }
        return false
    }

    private fun descriptorOf(method: java.lang.reflect.Method): String =
        descriptorOf(method.parameterTypes, descriptorOf(method.returnType))

    private fun descriptorOf(parameters: Array<Class<*>>, returns: String): String =
        parameters.joinToString("", "(", ")") { descriptorOf(it) } + returns

    /** `java.lang.String` -> `Ljava/lang/String;`, `int` -> `I`, `byte[]` -> `[B`. */
    private fun descriptorOf(type: Class<*>): String =
        when {
            type.isArray -> "[" + descriptorOf(type.componentType!!)
            type == java.lang.Byte.TYPE -> "B"
            type == java.lang.Character.TYPE -> "C"
            type == java.lang.Short.TYPE -> "S"
            type == Integer.TYPE -> "I"
            type == java.lang.Long.TYPE -> "J"
            type == java.lang.Boolean.TYPE -> "Z"
            type == java.lang.Float.TYPE -> "F"
            type == java.lang.Double.TYPE -> "D"
            type == java.lang.Void.TYPE -> RETURN_VOID
            else -> "L" + type.name.replace('.', '/') + ";"
        }

    private class MethodReference(val owner: String, val name: String, val descriptor: String)

    /** Reads only method references out of a class file's constant pool (JVMS 4.4). */
    private fun parseMethodReferences(classBytes: ByteArray): List<MethodReference> {
        DataInputStream(classBytes.inputStream().buffered()).use { input ->
            require(input.readInt() == 0xCAFEBABE.toInt()) { "Not a class file" }
            input.readUnsignedShort() // minor version
            input.readUnsignedShort() // major version
            val pool = readConstantPool(input, input.readUnsignedShort())

            return pool.methodRefs.mapNotNull { (classSlot, nameAndTypeSlot) ->
                val ownerNameSlot = pool.classNames[classSlot] ?: return@mapNotNull null
                val (nameSlot, descriptorSlot) = pool.nameAndTypes[nameAndTypeSlot] ?: return@mapNotNull null
                val owner = pool.utf8[ownerNameSlot] ?: return@mapNotNull null
                val name = pool.utf8[nameSlot] ?: return@mapNotNull null
                val descriptor = pool.utf8[descriptorSlot] ?: return@mapNotNull null
                MethodReference(owner, name, descriptor)
            }
        }
    }

    private class ConstantPool(
        val utf8: Map<Int, String>,
        val classNames: Map<Int, Int>,
        val nameAndTypes: Map<Int, Pair<Int, Int>>,
        val methodRefs: List<Pair<Int, Int>>,
    )

    private fun readConstantPool(input: DataInputStream, poolCount: Int): ConstantPool {
        val utf8 = HashMap<Int, String>()
        val classNames = HashMap<Int, Int>() // slot -> Utf8 slot
        val nameAndTypes = HashMap<Int, Pair<Int, Int>>() // slot -> (name, descriptor) Utf8 slots
        val methodRefs = ArrayList<Pair<Int, Int>>() // (class slot, nameAndType slot)

        var slot = 1
        while (slot < poolCount) {
            when (input.readUnsignedByte()) {
                TAG_UTF8 -> {
                    val buffer = ByteArray(input.readUnsignedShort())
                    input.readFully(buffer)
                    utf8[slot] = String(buffer, Charsets.UTF_8)
                }
                TAG_CLASS -> classNames[slot] = input.readUnsignedShort()
                TAG_NAME_AND_TYPE ->
                    nameAndTypes[slot] = input.readUnsignedShort() to input.readUnsignedShort()
                TAG_METHOD_REF, TAG_INTERFACE_METHOD_REF ->
                    methodRefs += input.readUnsignedShort() to input.readUnsignedShort()
                TAG_FIELD_REF, TAG_INVOKE_DYNAMIC ->
                    input.readUnsignedShort() to input.readUnsignedShort()
                TAG_STRING, TAG_METHOD_TYPE -> input.readUnsignedShort()
                TAG_METHOD_HANDLE -> {
                    input.readUnsignedByte() // reference_kind
                    input.readUnsignedShort() // reference_index
                }
                TAG_INTEGER, TAG_FLOAT -> input.readInt()
                TAG_LONG, TAG_DOUBLE -> {
                    input.readLong()
                    slot++ // long/double occupy two pool slots
                }
                // Dynamic and InvokeDynamic both carry two unsigned shorts.
                TAG_DYNAMIC -> {
                    input.readUnsignedShort()
                    input.readUnsignedShort()
                }
                TAG_MODULE, TAG_PACKAGE -> input.readUnsignedShort()
                else -> error("Unexpected constant-pool tag at slot $slot")
            }
            slot++
        }
        return ConstantPool(utf8, classNames, nameAndTypes, methodRefs)
    }

    @Test
    fun `a changed return type is unresolved`() {
        val owner = ReturnTypeFixture::class.java
        val ownerName = owner.name.replace('.', '/')
        assertTrue(
            "the fixture's own descriptor must resolve",
            declareOrInheritedMethod(owner, MethodReference(ownerName, "value", "()I")),
        )
        assertFalse(
            "a dependency that only changes the return type is not binary-compatible",
            declareOrInheritedMethod(owner, MethodReference(ownerName, "value", "()J")),
        )
    }

    @Test
    fun `dynamic constants keep the parser aligned`() {
        val references =
            parseMethodReferences(syntheticClassWithDynamicConstant())
                .map { "${it.owner}#${it.name}${it.descriptor}" }
        assertEquals(
            "a Dynamic constant carries two shorts; mis-reading it shifts every later entry",
            listOf("network/reticulum/Resource#create()V", "other/Clazz#run()V"),
            references,
        )
    }

    /** Constant pool: Utf8/Class/Methodref x2 with a Dynamic constant between them. */
    private fun syntheticClassWithDynamicConstant(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(out).use { data ->
            data.writeInt(0xCAFEBABE.toInt())
            data.writeShort(0) // minor
            data.writeShort(52) // major
            data.writeShort(14) // pool entries, including the implicit slot 0
            fun utf8(value: String) {
                data.writeByte(1)
                data.writeUTF(value)
            }
            utf8("network/reticulum/Resource") // 1
            data.writeByte(7); data.writeShort(1) // 2
            utf8("create") // 3
            utf8("()V") // 4
            data.writeByte(12); data.writeShort(3); data.writeShort(4) // 5
            data.writeByte(10); data.writeShort(2); data.writeShort(5) // 6: Methodref
            utf8("other/Clazz") // 7
            data.writeByte(7); data.writeShort(7) // 8
            utf8("run") // 9
            utf8("()V") // 10
            data.writeByte(12); data.writeShort(9); data.writeShort(10) // 11
            data.writeByte(10); data.writeShort(8); data.writeShort(11) // 12: Methodref
            data.writeByte(17); data.writeShort(0); data.writeShort(5) // 13: Dynamic
        }
        return out.toByteArray()
    }

    private class ReturnTypeFixture {
        @Suppress("unused", "FunctionOnlyReturningConstant")
        fun value(): Int = 1
    }

    private companion object {
        const val RETICULUM_PREFIX = "network/reticulum/"

        const val RETURN_VOID = "V"

        /** Set by :rns-backend-kt's test task (see build.gradle.kts). */
        const val RUNTIME_CLASSPATH_PROPERTY = "columba.test.runtimeClasspath"

        const val TAG_UTF8 = 1
        const val TAG_INTEGER = 3
        const val TAG_FLOAT = 4
        const val TAG_LONG = 5
        const val TAG_DOUBLE = 6
        const val TAG_CLASS = 7
        const val TAG_STRING = 8
        const val TAG_FIELD_REF = 9
        const val TAG_METHOD_REF = 10
        const val TAG_INTERFACE_METHOD_REF = 11
        const val TAG_NAME_AND_TYPE = 12
        const val TAG_METHOD_HANDLE = 15
        const val TAG_METHOD_TYPE = 16
        const val TAG_DYNAMIC = 17
        const val TAG_INVOKE_DYNAMIC = 18
        const val TAG_MODULE = 19
        const val TAG_PACKAGE = 20

        val PROTOCOL_ARTIFACTS = listOf("LXMF-kt", "rns-core", "LXST-kt", "rns-interfaces")
    }
}
