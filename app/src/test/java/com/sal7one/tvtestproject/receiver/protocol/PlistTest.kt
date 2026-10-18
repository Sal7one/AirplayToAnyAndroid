package com.sal7one.tvtestproject.receiver.protocol

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.Base64
import java.util.Date
import org.junit.Assert.*
import org.junit.Test

class PlistTest {
    @Test fun decodesIndependentPythonPlistIncludingUnsigned128Encoding() {
        // CPython plistlib.dumps(..., fmt=FMT_BINARY), not produced by our serializer.
        val fixture = "YnBsaXN0MDDTAQIDBAoLV3N0cmVhbXNUbmFtZVRkYXRhoQXSBgcICVR0eXBlXxASc3RyZWFtQ29ubmVjdGlvbklEEG4UAAAAAAAAAAD//////////2QAVABWACADu0MAAf8IDxccISMoLUJEVV4AAAAAAAABAQAAAAAAAAAMAAAAAAAAAAAAAAAAAAAAYg=="
        val root = Plist.decode(Base64.getDecoder().decode(fixture))
        val stream = (root["streams"] as List<*>)[0] as Map<*, *>
        assertEquals(110L, stream["type"])
        assertEquals(-1L, stream["streamConnectionID"])
        assertEquals("18446744073709551615", java.lang.Long.toUnsignedString(stream["streamConnectionID"] as Long))
        assertEquals("TV \u03bb", root["name"])
        assertArrayEquals(byteArrayOf(0, 1, -1), root["data"] as ByteArray)
    }

    @Test fun binaryRoundTripPreservesNestedTypesAndExtendedLengths() {
        val source = linkedMapOf<String, Any?>(
            "streams" to listOf(mapOf("type" to 110, "streamConnectionID" to Long.MIN_VALUE)),
            "data" to ByteArray(72) { it.toByte() }, "bool" to true, "null" to null,
            "real" to 1.5, "date" to Date(1_600_000_000_000), "uid" to Plist.Uid(-1),
            "unicode" to "TV \u03bb \ud83d\udcfa", "long" to "a".repeat(300),
        )
        val encoded = Plist.encode(source)
        assertEquals("bplist00", String(encoded, 0, 8, Charsets.US_ASCII))
        val result = Plist.decode(encoded)
        assertEquals(Long.MIN_VALUE, ((result["streams"] as List<*>)[0] as Map<*, *>)["streamConnectionID"])
        for (key in listOf("bool", "null", "real", "date", "uid", "unicode", "long")) assertEquals(source[key], result[key])
        assertArrayEquals(source["data"] as ByteArray, result["data"] as ByteArray)
    }

    @Test fun xmlRoundTripAndStandardAppleDoctype() {
        val source = mapOf("strings" to listOf("<&> \u03bb", "quoted\""), "data" to byteArrayOf(0, -1),
            "boolean" to false, "integer" to 65_536L, "real" to 0.125, "date" to Date(0))
        val xml = Plist.encodeXml(source)
        val result = Plist.decode(xml)
        assertEquals(source["strings"], result["strings"])
        assertEquals(65_536L, result["integer"])
        assertArrayEquals(source["data"] as ByteArray, result["data"] as ByteArray)
        val apple = "<?xml version=\"1.0\"?><!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\"><plist version=\"1.0\"><dict><key>streamConnectionID</key><integer>18446744073709551615</integer><key>s</key><string>A &amp; B</string></dict></plist>"
        val parsed = Plist.decode(apple.toByteArray())
        assertEquals(-1L, parsed["streamConnectionID"])
        assertEquals("A & B", parsed["s"])
    }

    @Test fun rejectsXmlEntitiesMalformedStructureDuplicateKeysAndOverflow() {
        val bad = listOf(
            "<!DOCTYPE plist [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><plist><dict><key>k</key><string>&x;</string></dict></plist>",
            "<plist><dict><key>k</key></dict></plist>",
            "<plist><dict><string>k</string><true/></dict></plist>",
            "<plist><dict><key>k</key><true/><key>k</key><false/></dict></plist>",
            "<plist><dict><key>k</key><integer>18446744073709551616</integer></dict></plist>",
            "<plist><dict><key>k</key><data>@@==</data></dict></plist>",
            "<plist><dict><key>k</key><real>NaN</real></dict></plist>",
            "<plist><dict><key>k</key><string><true/></string></dict></plist>",
            "<plist><array/></plist>", "not a plist with streams and ekey",
        )
        bad.forEach { assertThrows(Plist.PlistException::class.java) { Plist.decode(it.toByteArray()) } }
    }

    @Test fun rejectsBadBinaryReferencesCyclesWidthsAndObjectLengths() {
        val bad = listOf(
            binary(listOf(byteArrayOf(0xd1.toByte(), 1, -1), byteArrayOf(0x51, 0x6b))),
            binary(listOf(byteArrayOf(0xd1.toByte(), 1, 0), byteArrayOf(0x51, 0x6b))),
            binary(listOf(byteArrayOf(0xd2.toByte(), 1, 1, 2, 2), byteArrayOf(0x51, 0x6b), byteArrayOf(9))),
            binary(listOf(byteArrayOf(0xdf.toByte(), 0x13) + ByteArray(8) { -1 })),
            binary(listOf(byteArrayOf(0xd1.toByte(), 1, 2), byteArrayOf(0x5f, 0x11, -1, -1), byteArrayOf(9))),
            binary(listOf(byteArrayOf(0xd1.toByte(), 1, 2), byteArrayOf(0x51, 0x6b), byteArrayOf(0x15))),
        )
        bad.forEach { assertThrows(Plist.PlistException::class.java) { Plist.decode(it) } }
        val valid = Plist.encode(mapOf("k" to "v"))
        for (length in 0 until valid.size) assertThrows(Plist.PlistException::class.java) { Plist.decode(valid.copyOf(length)) }
        val badTrailer = valid.copyOf().also { it[it.size - 25] = 0 }
        assertThrows(Plist.PlistException::class.java) { Plist.decode(badTrailer) }
    }

    @Test fun enforcesDepthInputAndExpansionBudgets() {
        var nested: Any? = "end"
        repeat(10) { nested = listOf(nested) }
        val encoded = Plist.encode(mapOf("nested" to nested))
        assertThrows(Plist.PlistException::class.java) { Plist.decode(encoded, Plist.Limits(maxDepth = 3)) }
        assertThrows(Plist.PlistException::class.java) { Plist.encode(mapOf("nested" to nested), Plist.Limits(maxDepth = 3)) }
        assertThrows(Plist.PlistException::class.java) { Plist.decode(encoded, Plist.Limits(maxBytes = 32)) }
        val alias = binary(listOf(byteArrayOf(0xd1.toByte(), 1, 2), byteArrayOf(0x51, 0x6b),
            byteArrayOf(0xa4.toByte(), 3, 3, 3, 3), byteArrayOf(0x54, 0x61, 0x62, 0x63, 0x64)))
        assertThrows(Plist.PlistException::class.java) { Plist.decode(alias, Plist.Limits(maxExpandedBytes = 12)) }
        val cyclic = mutableListOf<Any?>().also { it.add(it) }
        assertThrows(Plist.PlistException::class.java) { Plist.encode(mapOf("cycle" to cyclic)) }
        assertThrows(Plist.PlistException::class.java) { Plist.encode(mapOf("big" to BigInteger.ONE.shiftLeft(65))) }
    }

    @Test fun xmlDepthLimitMatchesSerializerAtBoundary() {
        var value: Any? = "end"
        repeat(3) { value = listOf(value) }
        val limits = Plist.Limits(maxDepth = 4)
        val source = mapOf("nested" to value)
        assertEquals(source, Plist.decode(Plist.encodeXml(source, limits), limits))
        assertThrows(Plist.PlistException::class.java) { Plist.encodeXml(source, Plist.Limits(maxDepth = 3)) }
    }

    @Test fun rejectsInvalidUtf8AndUtf16() {
        assertThrows(Plist.PlistException::class.java) { Plist.decode(byteArrayOf(0xc0.toByte(), 0xaf.toByte())) }
        val invalid = binary(listOf(byteArrayOf(0xd1.toByte(), 1, 2), byteArrayOf(0x51, 0x6b), byteArrayOf(0x61, 0xd8.toByte(), 0)))
        assertThrows(Plist.PlistException::class.java) { Plist.decode(invalid) }
        assertThrows(Plist.PlistException::class.java) { Plist.encode(mapOf("s" to "\ud800")) }
    }

    @Test fun deterministicBinaryMutationsNeverEscapeParserErrorOrBounds() {
        val valid = Plist.encode(mapOf("streams" to listOf(mapOf("type" to 110, "streamConnectionID" to -1L)),
            "data" to ByteArray(72) { it.toByte() }))
        val random = java.util.Random(7748)
        repeat(2_000) {
            val mutated = valid.copyOf()
            repeat(1 + random.nextInt(3)) { mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte() }
            try { Plist.decode(mutated) } catch (_: Plist.PlistException) { }
        }
    }

    private fun binary(objects: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray())
        val offsets = objects.map { val offset = out.size(); out.write(it); offset }
        val table = out.size()
        offsets.forEach { out.write(it) }
        out.write(ByteArray(6)); out.write(1); out.write(1)
        out.write(ByteBuffer.allocate(24).putLong(objects.size.toLong()).putLong(0).putLong(table.toLong()).array())
        return out.toByteArray()
    }
}
