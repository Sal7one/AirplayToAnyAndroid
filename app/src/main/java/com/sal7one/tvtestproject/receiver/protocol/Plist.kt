package com.sal7one.tvtestproject.receiver.protocol

import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.Base64
import java.util.Date
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.ext.DefaultHandler2

/**
 * Bounded bplist00/XML property lists with structured dictionaries and arrays.
 * Binary format reference: https://github.com/apple-oss-distributions/CF/blob/main/CFBinaryPList.c
 * Scalars: null (binary only), Boolean, Long, Double, String, ByteArray, Date, Uid.
 * Integers preserve all uint64 bits in a Long; use Long.toUnsignedString(value) for
 * streamConnectionID, not value.toString(). XML integers up to 2^64-1 also preserve bits.
 * Unsupported markers/types, cyclic references, duplicate keys and oversized expansion fail.
 * UTF-8 XML permits the standard Apple DOCTYPE but never DTD entity declarations or external IO.
 */
object Plist {
    const val CONTENT_TYPE = "application/x-apple-binary-plist"
    class PlistException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
    data class Uid(val value: Long)
    data class Limits(
        val maxBytes: Int = 1024 * 1024,
        val maxObjects: Int = 16_384,
        val maxDepth: Int = 48,
        val maxValues: Int = 65_536,
        val maxExpandedBytes: Int = 4 * 1024 * 1024,
    ) {
        init { require(maxBytes > 0 && maxObjects > 0 && maxDepth in 1..128 && maxValues > 0 && maxExpandedBytes > 0) }
    }

    private val header = "bplist00".toByteArray(Charsets.US_ASCII)
    private val unsignedMax = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    private val signedMin = BigInteger.ONE.shiftLeft(63).negate()
    private const val APPLE_EPOCH_SECONDS = 978_307_200L
    private fun fail(message: String): Nothing = throw PlistException(message)

    fun decode(body: ByteArray, limits: Limits = Limits()): Map<String, Any?> {
        if (body.isEmpty() || body.size > limits.maxBytes) fail("Invalid plist size")
        val value = if (body.size >= 8 && body.copyOfRange(0, 8).contentEquals(header)) {
            BinaryReader(body, limits).read()
        } else {
            readXml(body, limits)
        }
        if (value !is Map<*, *>) fail("Plist root must be a dictionary")
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    fun encode(dict: Map<String, Any?>, limits: Limits = Limits()): ByteArray = BinaryWriter(limits).write(dict)

    fun encodeXml(dict: Map<String, Any?>, limits: Limits = Limits()): ByteArray {
        val budget = Budget(limits)
        val output = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\">")
        fun text(value: String) {
            budget.bytes(value.length.toLong() * 2)
            strictEncode(value, Charsets.UTF_8)
            value.forEach {
                when (it) {
                    '&' -> output.append("&amp;")
                    '<' -> output.append("&lt;")
                    '>' -> output.append("&gt;")
                    else -> {
                        if (it.code < 32 && it != '\t' && it != '\n' && it != '\r' || it == '\ufffe' || it == '\uffff') {
                            fail("Invalid XML character")
                        }
                        output.append(it)
                    }
                }
            }
        }
        fun node(value: Any?, depth: Int) {
            budget.value(depth)
            when (value) {
                is Map<*, *> -> {
                    output.append("<dict>")
                    value.forEach { (key, item) ->
                        if (key !is String) fail("Dictionary key must be a string")
                        budget.value(depth + 1)
                        output.append("<key>"); text(key); output.append("</key>")
                        node(item, depth + 1)
                    }
                    output.append("</dict>")
                }
                is List<*> -> { output.append("<array>"); value.forEach { node(it, depth + 1) }; output.append("</array>") }
                is String -> { output.append("<string>"); text(value); output.append("</string>") }
                is ByteArray -> {
                    budget.bytes(value.size.toLong())
                    output.append("<data>").append(Base64.getEncoder().encodeToString(value)).append("</data>")
                }
                is Boolean -> output.append(if (value) "<true/>" else "<false/>")
                is Byte, is Short, is Int, is Long -> output.append("<integer>").append(value).append("</integer>")
                is BigInteger -> { integerBits(value); output.append("<integer>").append(value).append("</integer>") }
                is Float, is Double -> {
                    val real = (value as Number).toDouble()
                    if (!real.isFinite()) fail("Non-finite real")
                    output.append("<real>").append(real).append("</real>")
                }
                is Date -> output.append("<date>").append(value.toInstant().toString()).append("</date>")
                else -> fail("Unsupported XML plist value")
            }
            if (output.length > limits.maxBytes) fail("Encoded plist too large")
        }
        node(dict, 0)
        output.append("</plist>")
        return strictEncode(output.toString(), Charsets.UTF_8).also { if (it.size > limits.maxBytes) fail("Encoded plist too large") }
    }

    private class Budget(val limits: Limits) {
        private var values = 0
        private var expanded = 0L
        fun value(depth: Int) {
            if (depth > limits.maxDepth || ++values > limits.maxValues) fail("Plist nesting/expansion limit exceeded")
        }
        fun bytes(count: Long) {
            expanded += count
            if (count < 0 || expanded > limits.maxExpandedBytes) fail("Plist data expansion limit exceeded")
        }
    }

    private fun integerBits(value: BigInteger): Long {
        if (value < signedMin || value > unsignedMax) fail("Plist integer is outside uint64/int64 range")
        return value.toLong()
    }

    private fun strictDecode(bytes: ByteArray, offset: Int, size: Int, charset: java.nio.charset.Charset): String = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, size)).toString()
    } catch (error: Exception) { throw PlistException("Invalid plist string encoding", error) }

    private fun strictEncode(text: String, charset: java.nio.charset.Charset): ByteArray = try {
        val buffer = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(text))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (error: Exception) { throw PlistException("Invalid plist string encoding", error) }

    private class BinaryReader(private val data: ByteArray, private val limits: Limits) {
        private val budget = Budget(limits)
        private val offsets: IntArray
        private val ends: IntArray
        private val active: BooleanArray
        private val refSize: Int
        private val root: Int

        init {
            if (data.size < 42) fail("Truncated binary plist")
            val trailer = data.size - 32
            if ((trailer until trailer + 6).any { data[it] != 0.toByte() }) fail("Invalid binary plist trailer")
            val offsetSize = data[trailer + 6].toInt() and 255
            refSize = data[trailer + 7].toInt() and 255
            if (offsetSize !in listOf(1, 2, 4, 8) || refSize !in listOf(1, 2, 4, 8)) fail("Invalid plist integer width")
            val count = bounded(unsigned(trailer + 8, 8, data.size), limits.maxObjects, "object count")
            if (count == 0) fail("Empty object table")
            root = bounded(unsigned(trailer + 16, 8, data.size), count - 1, "root reference")
            val table = bounded(unsigned(trailer + 24, 8, data.size), trailer, "offset table")
            if (table < 9 || table.toLong() + count.toLong() * offsetSize != trailer.toLong()) fail("Invalid offset table length")
            offsets = IntArray(count) { index ->
                bounded(unsigned(table + index * offsetSize, offsetSize, trailer), table - 1, "object offset").also {
                    if (it < 8) fail("Object overlaps plist header")
                }
            }
            val sorted = offsets.sorted()
            if (sorted.toSet().size != count) fail("Duplicate plist object offsets")
            val boundaries = sorted.mapIndexed { index, start -> start to (sorted.getOrNull(index + 1) ?: table) }.toMap()
            ends = IntArray(count) { checkNotNull(boundaries[offsets[it]]) }
            active = BooleanArray(count)
        }

        fun read(): Any? = node(root, 0)

        private fun bounded(value: Long, max: Int, label: String): Int {
            if (value < 0 || value > max.toLong()) fail("Invalid plist $label")
            return value.toInt()
        }

        private fun unsigned(offset: Int, width: Int, end: Int): Long {
            range(offset, width.toLong(), end)
            var value = 0L
            repeat(width) { value = (value shl 8) or (data[offset + it].toLong() and 255) }
            return value
        }

        private fun range(offset: Int, length: Long, end: Int) {
            if (offset < 0 || length < 0 || offset.toLong() + length > end) fail("Truncated plist object")
        }

        private fun node(index: Int, depth: Int): Any? {
            budget.value(depth)
            if (active[index]) fail("Cyclic plist reference")
            active[index] = true
            try {
                val end = ends[index]
                var position = offsets[index]
                val marker = data[position++].toInt() and 255
                val type = marker ushr 4
                val info = marker and 15
                fun length(): Int {
                    if (info != 15) return info
                    range(position, 1, end)
                    val intMarker = data[position++].toInt() and 255
                    if (intMarker ushr 4 != 1 || intMarker and 15 > 3) fail("Invalid extended plist length")
                    val width = 1 shl (intMarker and 15)
                    val result = bounded(unsigned(position, width, end), limits.maxExpandedBytes, "object length")
                    position += width
                    return result
                }
                fun reference(at: Int): Any? = node(bounded(unsigned(at, refSize, end), offsets.size - 1, "reference"), depth + 1)
                return when (type) {
                    0 -> when (info) { 0 -> null; 8 -> false; 9 -> true; else -> fail("Unsupported plist simple marker") }
                    1 -> {
                        if (info > 4) fail("Oversized plist integer")
                        val width = 1 shl info
                        if (width <= 8) unsigned(position, width, end) else {
                            range(position, 16, end)
                            integerBits(BigInteger(data.copyOfRange(position, position + 16)))
                        }
                    }
                    2 -> {
                        val value = when (info) {
                            2 -> java.lang.Float.intBitsToFloat(unsigned(position, 4, end).toInt()).toDouble()
                            3 -> java.lang.Double.longBitsToDouble(unsigned(position, 8, end))
                            else -> fail("Invalid plist real width")
                        }
                        if (!value.isFinite()) fail("Non-finite plist real")
                        value
                    }
                    3 -> {
                        if (info != 3) fail("Invalid plist date marker")
                        val seconds = java.lang.Double.longBitsToDouble(unsigned(position, 8, end))
                        val millis = (seconds + APPLE_EPOCH_SECONDS) * 1000
                        if (!millis.isFinite() || millis < Long.MIN_VALUE.toDouble() || millis >= Long.MAX_VALUE.toDouble()) fail("Invalid plist date")
                        Date(millis.toLong())
                    }
                    4, 5, 6, 7 -> {
                        val count = length()
                        val bytes = count.toLong() * if (type == 6) 2 else 1
                        range(position, bytes, end)
                        budget.bytes(bytes)
                        when (type) {
                            4 -> data.copyOfRange(position, position + count)
                            5 -> strictDecode(data, position, count, Charsets.US_ASCII)
                            6 -> strictDecode(data, position, bytes.toInt(), Charsets.UTF_16BE)
                            else -> strictDecode(data, position, count, Charsets.UTF_8)
                        }
                    }
                    8 -> {
                        if (info > 7) fail("Oversized plist UID")
                        Uid(unsigned(position, info + 1, end))
                    }
                    10, 13 -> {
                        val count = length()
                        if (count > limits.maxValues) fail("Plist container too large")
                        val slots = count.toLong() * if (type == 13) 2 else 1
                        range(position, slots * refSize, end)
                        if (type == 10) List(count) { reference(position + it * refSize) } else {
                            val result = linkedMapOf<String, Any?>()
                            repeat(count) {
                                val key = reference(position + it * refSize) as? String ?: fail("Dictionary key must be a string")
                                if (key in result) fail("Duplicate plist dictionary key")
                                result[key] = reference(position + (count + it) * refSize)
                            }
                            result
                        }
                    }
                    else -> fail("Unsupported plist object marker")
                }
            } finally { active[index] = false }
        }
    }

    private class BinaryWriter(private val limits: Limits) {
        private class Node(val value: Any?, val references: List<Int> = emptyList())
        private val nodes = mutableListOf<Node>()
        private val budget = Budget(limits)

        private fun add(value: Any?, depth: Int): Int {
            budget.value(depth)
            if (nodes.size >= limits.maxObjects) fail("Too many plist objects")
            val index = nodes.size
            nodes.add(Node(null))
            val refs = when (value) {
                is Map<*, *> -> {
                    if (value.size > limits.maxObjects / 2) fail("Dictionary too large")
                    val keys = value.keys.map { if (it !is String) fail("Dictionary key must be a string"); add(it, depth + 1) }
                    keys + value.values.map { add(it, depth + 1) }
                }
                is List<*> -> {
                    if (value.size > limits.maxObjects) fail("Array too large")
                    value.map { add(it, depth + 1) }
                }
                else -> emptyList()
            }
            nodes[index] = Node(value, refs)
            return index
        }

        fun write(dict: Map<String, Any?>): ByteArray {
            val root = add(dict, 0)
            val refWidth = width((nodes.size - 1).toLong())
            val output = ByteArrayOutputStream()
            output.write(header)
            val offsets = mutableListOf<Int>()
            fun number(value: Long, size: Int) { for (i in size - 1 downTo 0) output.write((value ushr (i * 8)).toInt() and 255) }
            fun length(type: Int, count: Int) {
                if (count < 15) output.write(type or count) else {
                    output.write(type or 15)
                    val size = width(count.toLong())
                    output.write(0x10 or logWidth(size))
                    number(count.toLong(), size)
                }
            }
            nodes.forEach { node ->
                offsets.add(output.size())
                when (val value = node.value) {
                    null -> output.write(0)
                    is Boolean -> output.write(if (value) 9 else 8)
                    is Byte, is Short, is Int, is Long, is BigInteger -> {
                        val bits = if (value is BigInteger) integerBits(value) else (value as Number).toLong()
                        val size = width(bits)
                        output.write(0x10 or logWidth(size)); number(bits, size)
                    }
                    is Float, is Double -> {
                        val real = (value as Number).toDouble()
                        if (!real.isFinite()) fail("Non-finite real")
                        output.write(0x23); number(java.lang.Double.doubleToRawLongBits(real), 8)
                    }
                    is Date -> {
                        output.write(0x33)
                        number(java.lang.Double.doubleToRawLongBits(value.time.toDouble() / 1000 - APPLE_EPOCH_SECONDS), 8)
                    }
                    is Uid -> { val size = width(value.value); output.write(0x80 or (size - 1)); number(value.value, size) }
                    is ByteArray -> { budget.bytes(value.size.toLong()); length(0x40, value.size); output.write(value) }
                    is String -> {
                        val ascii = value.all { it.code < 128 }
                        val byteCount = value.length.toLong() * if (ascii) 1 else 2
                        if (byteCount > limits.maxBytes) fail("Plist string too large")
                        budget.bytes(byteCount)
                        val bytes = strictEncode(value, if (ascii) Charsets.US_ASCII else Charsets.UTF_16BE)
                        length(if (ascii) 0x50 else 0x60, value.length); output.write(bytes)
                    }
                    is List<*>, is Map<*, *> -> {
                        length(if (value is List<*>) 0xa0 else 0xd0, if (value is List<*>) value.size else (value as Map<*, *>).size)
                        node.references.forEach { number(it.toLong(), refWidth) }
                    }
                    else -> fail("Unsupported binary plist value")
                }
                if (output.size() > limits.maxBytes) fail("Encoded plist too large")
            }
            val table = output.size()
            val offsetWidth = width(table.toLong())
            offsets.forEach { number(it.toLong(), offsetWidth) }
            output.write(ByteArray(6)); output.write(offsetWidth); output.write(refWidth)
            number(nodes.size.toLong(), 8); number(root.toLong(), 8); number(table.toLong(), 8)
            if (output.size() > limits.maxBytes) fail("Encoded plist too large")
            return output.toByteArray()
        }

        private fun width(value: Long): Int = when { value < 0 -> 8; value <= 255 -> 1; value <= 65535 -> 2; value <= 0xffffffffL -> 4; else -> 8 }
        private fun logWidth(value: Int): Int = when (value) { 1 -> 0; 2 -> 1; 4 -> 2; else -> 3 }
    }

    private fun readXml(body: ByteArray, limits: Limits): Any? {
        val xml = strictDecode(body, 0, body.size, Charsets.UTF_8).removePrefix("\ufeff")
        // XML entity declarations are forbidden before SAX can expand parameter/internal entities.
        // UTF-8 is decoded strictly first, so alternate encodings cannot bypass this guard.
        if (xml.contains("<!ENTITY")) fail("XML entity declarations are forbidden")
        val handler = XmlHandler(limits)
        try {
            val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.entityResolver = handler
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            reader.parse(InputSource(StringReader(xml)))
            return handler.result ?: fail("Missing XML plist root")
        } catch (error: PlistException) { throw error
        } catch (error: Exception) { throw PlistException("Invalid XML plist", error) }
    }

    private class XmlHandler(private val limits: Limits) : DefaultHandler2() {
        private class Node(val name: String) {
            val text = StringBuilder()
            val children = mutableListOf<Pair<String, Any?>>()
        }
        private val budget = Budget(limits)
        private val stack = java.util.ArrayDeque<Node>()
        private var objects = 0
        private var textCount = 0L
        private var rootSeen = false
        var result: Any? = null

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))
        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource = InputSource(StringReader(""))
        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            if (name != "plist" || publicId != "-//Apple//DTD PLIST 1.0//EN" ||
                systemId !in setOf("http://www.apple.com/DTDs/PropertyList-1.0.dtd", "https://www.apple.com/DTDs/PropertyList-1.0.dtd")) {
                fail("Unsupported XML DOCTYPE")
            }
        }
        override fun startEntity(name: String?) {
            if (name !in setOf("amp", "lt", "gt", "apos", "quot", "[dtd]")) fail("XML entities are forbidden")
        }
        override fun error(error: org.xml.sax.SAXParseException) { throw error }
        override fun fatalError(error: org.xml.sax.SAXParseException) { throw error }
        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            if (localName != "plist") {
                budget.value((stack.size - 1).coerceAtLeast(0))
                if (++objects > limits.maxObjects) fail("Too many XML plist objects")
            }
            if (uri.isNotEmpty()) fail("Invalid XML plist element")
            if (stack.isEmpty()) {
                if (rootSeen || localName != "plist") fail("Invalid XML root")
                rootSeen = true
                if (attributes.length > 1 || attributes.length == 1 &&
                    (attributes.getQName(0) != "version" || attributes.getValue(0) != "1.0")) fail("Invalid plist version")
            } else if (attributes.length != 0 || localName == "plist") fail("Invalid XML plist attributes")
            if (localName !in setOf("plist", "dict", "array", "key", "string", "integer", "real", "data", "date", "true", "false")) {
                fail("Unsupported XML plist element")
            }
            stack.addLast(Node(localName))
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            textCount += length
            if (textCount > limits.maxExpandedBytes) fail("XML text too large")
            if (stack.isEmpty()) {
                if ((start until start + length).any { !ch[it].isWhitespace() }) fail("Text outside XML plist")
            } else checkNotNull(stack.peekLast()).text.append(ch, start, length)
        }
        override fun endElement(uri: String, localName: String, qName: String) {
            val current = stack.removeLast()
            val text = current.text.toString()
            val children = current.children
            if (current.name != localName) fail("Mismatched XML element")
            if (localName !in setOf("plist", "dict", "array") && children.isNotEmpty()) fail("Children in XML scalar")
            val value: Any? = when (localName) {
                "plist" -> {
                    if (children.size != 1 || children[0].first != "dict" || text.isNotBlank()) fail("Invalid XML plist root")
                    children[0].second
                }
                "dict" -> {
                    if (children.size % 2 != 0 || text.isNotBlank()) fail("Invalid XML dictionary")
                    val map = linkedMapOf<String, Any?>()
                    children.chunked(2).forEach {
                        if (it[0].first != "key" || it[1].first == "key") fail("Invalid XML dictionary entry")
                        val key = it[0].second as String
                        if (key in map) fail("Duplicate XML dictionary key")
                        map[key] = it[1].second
                    }
                    map
                }
                "array" -> {
                    if (text.isNotBlank() || children.any { it.first == "key" }) fail("Invalid XML array")
                    children.map { it.second }
                }
                "string", "key" -> { budget.bytes(text.length.toLong() * 2); text }
                "integer" -> {
                    val number = text.trim()
                    if (!number.matches(Regex("[+-]?[0-9]{1,20}"))) fail("Invalid XML integer")
                    integerBits(BigInteger(number))
                }
                "real" -> text.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail("Invalid XML real")
                "true", "false" -> { if (text.isNotBlank()) fail("Text in XML boolean"); localName == "true" }
                "data" -> {
                    val base64 = text.filterNot { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
                    if (base64.length % 4 != 0) fail("Invalid plist base64")
                    val decoded = try { Base64.getDecoder().decode(base64) } catch (error: Exception) { throw PlistException("Invalid plist base64", error) }
                    budget.bytes(decoded.size.toLong())
                    decoded
                }
                "date" -> try { Date.from(Instant.parse(text.trim())) } catch (error: Exception) { throw PlistException("Invalid XML date", error) }
                else -> fail("Unsupported XML plist element")
            }
            if (stack.isEmpty()) result = value else checkNotNull(stack.peekLast()).children.add(localName to value)
        }
    }
}
