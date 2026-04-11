package com.lxmf.messenger.reticulum.protocol

import org.msgpack.core.MessagePacker
import org.msgpack.core.MessageUnpacker

internal object MsgpackHelper {
    fun packValue(
        packer: MessagePacker,
        value: Any?,
    ) {
        when (value) {
            null -> packer.packNil()
            is ByteArray -> {
                packer.packBinaryHeader(value.size)
                packer.writePayload(value)
            }
            is String -> packer.packString(value)
            is Int -> packer.packInt(value)
            is Long -> packer.packLong(value)
            is Float -> packer.packFloat(value)
            is Double -> packer.packDouble(value)
            is Boolean -> packer.packBoolean(value)
            is List<*> -> {
                packer.packArrayHeader(value.size)
                value.forEach { item -> packValue(packer, item) }
            }
            is Map<*, *> -> {
                packer.packMapHeader(value.size)
                value.forEach { (k, v) ->
                    packValue(packer, k)
                    packValue(packer, v)
                }
            }
            is Number -> packer.packLong(value.toLong())
            else -> packer.packString(value.toString())
        }
    }

    fun unpackValue(unpacker: MessageUnpacker): Any? {
        val format = unpacker.nextFormat
        return when (format.valueType.name) {
            "NIL" -> {
                unpacker.unpackNil()
                null
            }
            "BOOLEAN" -> unpacker.unpackBoolean()
            "INTEGER" -> unpacker.unpackLong()
            "FLOAT" -> unpacker.unpackDouble()
            "STRING" -> unpacker.unpackString()
            "BINARY" -> {
                val len = unpacker.unpackBinaryHeader()
                val bytes = ByteArray(len)
                unpacker.readPayload(bytes)
                bytes
            }
            "ARRAY" -> {
                val size = unpacker.unpackArrayHeader()
                MutableList(size) { unpackValue(unpacker) }
            }
            "MAP" -> {
                val size = unpacker.unpackMapHeader()
                mutableMapOf<Any?, Any?>().also { map ->
                    repeat(size) {
                        map[unpackValue(unpacker)] = unpackValue(unpacker)
                    }
                }
            }
            else -> {
                unpacker.skipValue()
                null
            }
        }
    }
}
