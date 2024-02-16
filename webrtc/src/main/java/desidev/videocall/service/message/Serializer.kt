package desidev.videocall.service.message

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.typeOf


annotation class SymbolId(val id: Int)

private interface RefWriter {
    fun write(refValue: Int)
}

private interface RefReader {
    fun read(): Int
}

fun <T : Any> serializeMessage(obj: T, refSize: Int = 4): ByteArray {
    val clazz = obj::class as KClass<T>

    val baos = ByteArrayOutputStream()
    val os = DataOutputStream(baos)

    var nextRef = 1
    val properties = clazz.memberProperties
        .sortedBy { prop ->
            prop.findAnnotation<SymbolId>()?.id ?: Int.MAX_VALUE
        }

    val classId = clazz.findAnnotation<SymbolId>()!!.id
    os.write(classId)

    val refWriter = when (refSize) {
        4 -> {
            object : RefWriter {
                override fun write(refValue: Int) {
                    os.writeInt(refValue)
                }
            }
        }

        2 -> {
            object : RefWriter {
                override fun write(refValue: Int) {
                    os.writeShort(refValue)
                }
            }
        }

        else -> {
            throw IllegalArgumentException("Not a valid refSize")
        }
    }


    for (property in properties) {
        when (property.returnType) {
            typeOf<Int>() -> {
                refWriter.write(nextRef)
                nextRef += 4
            }

            typeOf<Long>() -> {
                refWriter.write(nextRef)
                nextRef += 8
            }

            typeOf<String>() -> {
                refWriter.write(nextRef)
                nextRef += (property.get(obj) as String).length
            }

            typeOf<ByteArray>() -> {
                refWriter.write(nextRef)
                nextRef += (property.get(obj) as ByteArray).size
            }

            typeOf<Int?>() -> {
                if ((property.get(obj) as Int?) != null) {
                    refWriter.write(nextRef)
                    nextRef += 4
                } else {
                    refWriter.write(0)
                }
            }

            typeOf<ByteArray?>() -> {
                val value = (property.get(obj) as ByteArray?)
                if (value != null) {
                    refWriter.write(nextRef)
                    nextRef += value.size
                } else {
                    refWriter.write(0) // null reference
                }
            }
        }
    }

    for (property in properties) {
        when (property.returnType) {
            typeOf<Int>() -> {
                os.writeInt(property.get(obj) as Int)
            }

            typeOf<Long>() -> {
                os.writeLong(property.get(obj) as Long)
            }

            typeOf<String>() -> {
                os.write((property.get(obj) as String).encodeToByteArray())
            }

            typeOf<ByteArray>() -> {
                os.write(property.get(obj) as ByteArray)
            }

            typeOf<Int?>() -> {
                val value = property.get(obj) as Int?
                if (value != null) {
                    os.writeInt(value)
                }
            }

            typeOf<ByteArray?>() -> {
                val value = (property.get(obj) as ByteArray?)
                if (value != null) {
                    os.write(value)
                }
            }
        }
    }

    os.flush()
    baos.close()
    os.close()
    return baos.toByteArray()
}

fun deserializeMessage(messageBytes: ByteArray, refSize: Int = 4): Message {
    val messageId = messageBytes[0].toInt()
    val propBytesStop = when (messageId) {
        VideoFormat::class.findAnnotation<SymbolId>()!!.id -> {
            1 + refSize * 16
        }

        AudioFormat::class.findAnnotation<SymbolId>()!!.id -> {
            1 + refSize * 7
        }

        AudioSample::class.findAnnotation<SymbolId>()!!.id -> {
            1 + 3 * refSize
        }

        VideoSample::class.findAnnotation<SymbolId>()!!.id -> {
            1 + 3 * refSize
        }

        FormatAccept::class.findAnnotation<SymbolId>()!!.id -> {
            1
        }

        else -> throw IllegalArgumentException("Not a Valid Message!")
    }
    val propBytes = messageBytes.copyOfRange(1, propBytesStop)
    val propBuffer = ByteBuffer.wrap(propBytes)
    val contentBytes = messageBytes.copyOfRange(propBytesStop, messageBytes.size)
    val refReader = when (refSize) {
        4 -> {
            object : RefReader {
                override fun read(): Int {
                    return if (propBuffer.hasRemaining()) propBuffer.int else -1
                }
            }
        }

        2 -> {
            object : RefReader {
                override fun read(): Int {
                    return propBuffer.short.toInt()
                }
            }
        }

        else -> {
            throw IllegalArgumentException("Not a valid refsize")
        }
    }

    val refs = buildList {
        while (true) {
            val refValue = refReader.read()
            if (refValue < 0) break
            add(refValue)
        }
        add(contentBytes.size + 1)
    }

    val contentQue = ArrayDeque<ByteArray>()

    val contentRef = refs.filter { ref -> ref != 0 }.map { it - 1 }
    for (i in contentRef.lastIndex downTo 1) {
        val from = contentRef[i - 1]
        val to = contentRef[i]
        val bytes = contentBytes.copyOfRange(from, to)
        contentQue.add(bytes)
    }

    return when (messageId) {
        VideoFormat::class.findAnnotation<SymbolId>()!!.id -> {
            VideoFormat(
                mime = convert { contentQue.removeLast().decodeToString() },
                framerate = convert { ByteBuffer.wrap(contentQue.removeLast()).int },
                rotation = convert { ByteBuffer.wrap(contentQue.removeLast()).int },
                width = convert { ByteBuffer.wrap(contentQue.removeLast()).int },
                height = convert { ByteBuffer.wrap(contentQue.removeLast()).int },
                maxWidth = convert {
                    if (refs[5] != 0) {
                        ByteBuffer.wrap(contentQue.removeLast()).int
                    } else {
                        null
                    }
                },
                maxHeight = convert {
                    if (refs[6] == 0) {
                        null
                    } else {
                        ByteBuffer.wrap(contentQue.removeLast()).int
                    }
                },
                colorFormat = convert {
                    if (refs[7] == 0) {
                        null
                    } else {
                        ByteBuffer.wrap(contentQue.removeLast()).int
                    }
                },
                bitrate = convert { ByteBuffer.wrap(contentQue.removeLast()).int },
                maxBitrate = convert { ByteBuffer.wrap(contentQue.removeLast()).int },
                colorRange = convert {
                    if (refs[10] == 0) {
                        null
                    } else {
                        ByteBuffer.wrap(contentQue.removeLast()).int
                    }
                },
                colorStandard = convert {
                    if (refs[11] == 0) {
                        null
                    } else {
                        ByteBuffer.wrap(contentQue.removeLast()).int
                    }
                },
                colorTransfer = convert {
                    if (refs[12] == 0) {
                        null
                    } else {
                        ByteBuffer.wrap(contentQue.removeLast()).int
                    }
                },
                csd0 = convert {
                    if (refs[13] != 0) {
                        contentQue.removeLast()
                    } else {
                        null
                    }
                },
                csd1 = convert {
                    if (refs[14] != 0) {
                        contentQue.removeLast()
                    } else {
                        null
                    }
                },
                csd2 = convert {
                    if (refs[15] != 0) {
                        contentQue.removeLast()
                    } else {
                        null
                    }
                }
            )
        }

        AudioFormat::class.findAnnotation<SymbolId>()!!.id -> {
            AudioFormat(
                mime = convert { contentQue.removeLast().decodeToString() },
                bitrate = convert { contentQue.removeLast().let { ByteBuffer.wrap(it).int } },
                channelCount = convert { contentQue.removeLast().let { ByteBuffer.wrap(it).int } },
                sampleRate = convert { contentQue.removeLast().let { ByteBuffer.wrap(it).int } },
                csd0 = convert {
                    if (refs[4] != 0) {
                        contentQue.removeLast()
                    } else {
                        null
                    }
                },
                csd1 = convert {
                    if (refs[5] != 0) {
                        contentQue.removeLast()
                    } else {
                        null
                    }
                },
                csd2 = convert {
                    if (refs[6] != 0) {
                        contentQue.removeLast()
                    } else {
                        null
                    }
                }
            )
        }

        VideoSample::class.findAnnotation<SymbolId>()!!.id -> {
            VideoSample(
                timeStamp = contentQue.removeLast().let { ByteBuffer.wrap(it).long },
                flag = contentQue.removeLast().let { ByteBuffer.wrap(it).int },
                sample = contentQue.removeLast()
            )
        }

        AudioSample::class.findAnnotation<SymbolId>()!!.id -> {
            AudioSample(
                timeStampUs = contentQue.removeLast().let { ByteBuffer.wrap(it).long },
                flag = contentQue.removeLast().let { ByteBuffer.wrap(it).int },
                sample = contentQue.removeLast()
            )
        }

        FormatAccept::class.findAnnotation<SymbolId>()!!.id -> {
            FormatAccept
        }

        else -> throw IllegalArgumentException("Input bytes not a valid message")
    }
}

private inline fun <T> convert(parser: () -> T): T {
    return parser()
}