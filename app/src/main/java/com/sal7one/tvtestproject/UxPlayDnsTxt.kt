package com.sal7one.tvtestproject

import com.sal7one.tvtestproject.receiver.AudioCodec
import com.sal7one.tvtestproject.receiver.ReceiverAnnouncement
import java.util.Hashtable
import java.util.UUID

/** Legacy H.264 mirror discovery, populated from the engine's actual bound endpoint and keys. */
object UxPlayDnsTxt {
    const val MODEL = "AppleTV3,2"
    const val SOURCE_VERSION = "220.68"
    const val STATUS_FLAGS = 0x4

    // UxPlay lib/dnssdint.h documents cn: 0 PCM, 1 ALAC, 2 AAC, 3 AAC-ELD.
    // No metadata, HLS, photos, or HEVC. The engine provides its actual feature mask.
    // Screen=7, Audio=9, LegacyPairing=27, RAOP=30. AudioFormat feature bits
    // 18..21 map to PCM/ALAC/AAC/ELD; the engine gates them by decoder support.
    // Sources: https://github.com/FDH2/UxPlay/blob/master/lib/dnssdint.h
    // https://github.com/openairplay/airplay-spec/blob/master/index.html
    fun airplayTxt(announcement: ReceiverAnnouncement): Hashtable<String, String> {
        validate(announcement)
        return Hashtable<String, String>().apply {
            put("deviceid", announcement.deviceId)
            put("features", featurePair(announcement.features))
            put("pw", "false")
            put("flags", "0x4")
            put("model", MODEL)
            put("pk", announcement.publicKeyHex)
            put("pi", announcement.pairingId)
            put("srcvers", SOURCE_VERSION)
            put("vv", "2")
        }
    }

    fun raopTxt(announcement: ReceiverAnnouncement): Hashtable<String, String> {
        validate(announcement)
        return Hashtable<String, String>().apply {
            put("txtvers", "1")
            put("ch", "2")
            put("cn", audioCodecList(announcement.audioCodecs))
            put("et", "5")
            put("ft", featurePair(announcement.features))
            put("am", MODEL)
            put("pw", "false")
            put("sf", "0x4")
            put("sr", "44100")
            put("ss", "16")
            put("tp", "UDP")
            put("vs", SOURCE_VERSION)
            put("vn", "65537")
            put("pk", announcement.publicKeyHex)
            put("vv", "2")
        }
    }

    fun audioCodecList(codecs: Set<AudioCodec>): String = buildList {
        if (AudioCodec.PCM in codecs) add("0")
        if (AudioCodec.ALAC in codecs) add("1")
        if (AudioCodec.AAC_LC in codecs) add("2")
        if (AudioCodec.AAC_ELD in codecs) add("3")
    }.joinToString(",")

    fun featurePair(features: Long): String =
        "0x${java.lang.Long.toHexString(features and 0xffff_ffffL)}," +
            "0x${java.lang.Long.toHexString(features ushr 32)}"

    fun validate(announcement: ReceiverAnnouncement) {
        require(announcement.port in 1..65535) { "Receiver did not return a bound TCP port" }
        require(announcement.name.isNotBlank() && announcement.name.toByteArray(Charsets.UTF_8).size <= 63) {
            "Receiver name must fit a DNS service label"
        }
        require(Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}").matches(announcement.deviceId)) {
            "Receiver returned an invalid device ID"
        }
        require(Regex("[0-9a-fA-F]{64}").matches(announcement.publicKeyHex)) {
            "Receiver did not return an Ed25519 public key"
        }
        UUID.fromString(announcement.pairingId)
        require(DeviceIdentity.raopServiceInstanceName(announcement.deviceId, announcement.name)
            .toByteArray(Charsets.UTF_8).size <= 63) { "Receiver name must fit a RAOP service label" }
        require(announcement.audioCodecs.isNotEmpty()) { "Receiver has no supported audio codec" }
    }
}
