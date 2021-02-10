package org.ktorm.r2dbc.database

import io.r2dbc.spi.Blob
import io.r2dbc.spi.Clob
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import java.math.BigDecimal
import java.time.*
import java.util.*

/**
 * Created by vince on Feb 10, 2021.
 */
public open class CachedRow(row: Row, metadata: RowMetadata): Row {
    private val values = metadata.columnMetadatas.associate { it.name.toUpperCase() to row[it.name] }

    public val metadata: RowMetadata = if (metadata is CachedRowMetadata) metadata else CachedRowMetadata(metadata)

    override fun <T : Any> get(index: Int, type: Class<T>): T? {
        val column = metadata.getColumnMetadata(index)
        return get(column.name, type)
    }

    override fun <T : Any> get(name: String, type: Class<T>): T? {
        val result = when (type.kotlin) {
            String::class -> getString(name)
            Boolean::class -> getBoolean(name)
            Byte::class -> getByte(name)
            Short::class -> getShort(name)
            Int::class -> getInt(name)
            Long::class -> getLong(name)
            Float::class -> getFloat(name)
            Double::class -> getDouble(name)
            BigDecimal::class -> getBigDecimal(name)
            ByteArray::class -> getBytes(name)
            else -> getColumnValue(name)
        }

        return type.cast(result)
    }

    private fun getColumnValue(name: String): Any? {
        return values[name.toUpperCase()]
    }

    private fun getString(name: String): String? {
        return when (val value = getColumnValue(name)) {
            is String -> value
            is Clob -> "" // todo: clob
            else -> value?.toString()
        }
    }

    private fun getBoolean(name: String): Boolean? {
        return when (val value = getColumnValue(name)) {
            null -> null
            is Boolean -> value
            is Number -> value.toDouble().toBits() != 0.0.toBits()
            else -> value.toString().toDouble().toBits() != 0.0.toBits()
        }
    }

    private fun getByte(name: String): Byte? {
        return when (val value = getColumnValue(name)) {
            is Byte -> value
            is Number -> value.toByte()
            is Boolean -> if (value) 1 else 0
            else -> value?.toString()?.toByte()
        }
    }

    private fun getShort(name: String): Short? {
        return when (val value = getColumnValue(name)) {
            is Short -> value
            is Number -> value.toShort()
            is Boolean -> if (value) 1 else 0
            else -> value?.toString()?.toShort()
        }
    }

    private fun getInt(name: String): Int? {
        return when (val value = getColumnValue(name)) {
            is Int -> value
            is Number -> value.toInt()
            is Boolean -> if (value) 1 else 0
            else -> value?.toString()?.toInt()
        }
    }

    private fun getLong(name: String): Long? {
        return when (val value = getColumnValue(name)) {
            is Long -> value
            is Number -> value.toLong()
            is Boolean -> if (value) 1 else 0
            else -> value?.toString()?.toLong()
        }
    }

    private fun getFloat(name: String): Float? {
        return when (val value = getColumnValue(name)) {
            is Float -> value
            is Number -> value.toFloat()
            is Boolean -> if (value) 1.0F else 0.0F
            else -> value?.toString()?.toFloat()
        }
    }

    private fun getDouble(name: String): Double? {
        return when (val value = getColumnValue(name)) {
            is Double -> value
            is Number -> value.toDouble()
            is Boolean -> if (value) 1.0 else 0.0
            else -> value?.toString()?.toDouble()
        }
    }

    private fun getBigDecimal(name: String): BigDecimal? {
        return when (val value = getColumnValue(name)) {
            is BigDecimal -> value
            is Boolean -> if (value) BigDecimal.ONE else BigDecimal.ZERO
            else -> value?.toString()?.toBigDecimal()
        }
    }

    private fun getBytes(name: String): ByteArray? {
        return when (val value = getColumnValue(name)) {
            null -> null
            is ByteArray -> Arrays.copyOf(value, value.size)
            is Blob -> TODO("blob/bytebuffer")
            else -> throw IllegalArgumentException("Cannot convert ${value.javaClass.name} value to byte[].")
        }
    }

    private fun getDateTime(name: String): LocalDateTime? {
        return when (val value = getColumnValue(name)) {
            null -> null
            is LocalDateTime -> value
            is LocalDate -> value.atStartOfDay()
            is ZonedDateTime -> value.toLocalDateTime()
            is OffsetDateTime -> value.toLocalDateTime()
            is Number -> Instant.ofEpochMilli(value.toLong()).atZone(ZoneId.systemDefault()).toLocalDateTime()
            is String -> {
                val number = value.toLongOrNull()
                if (number != null) {
                    Instant.ofEpochMilli(number).atZone(ZoneId.systemDefault()).toLocalDateTime()
                } else {
                    LocalDateTime.parse(value)
                }
            }
            else -> {
                throw IllegalArgumentException("Cannot convert ${value.javaClass.name} value to LocalDateTime.")
            }
        }
    }

    private fun getDate(name: String): LocalDate? {
        return when (val value = getColumnValue(name)) {
            null -> null
            is LocalDate -> value
            is LocalDateTime -> value.toLocalDate()
            is ZonedDateTime -> value.toLocalDate()
            is OffsetDateTime -> value.toLocalDate()
            is Number -> Instant.ofEpochMilli(value.toLong()).atZone(ZoneId.systemDefault()).toLocalDate()
            is String -> {
                val number = value.toLongOrNull()
                if (number != null) {
                    Instant.ofEpochMilli(number).atZone(ZoneId.systemDefault()).toLocalDate()
                } else {
                    LocalDate.parse(value)
                }
            }
            else -> {
                throw IllegalArgumentException("Cannot convert ${value.javaClass.name} value to LocalDate.")
            }
        }
    }

    private fun getTime(name: String): LocalTime? {
        return when (val value = getColumnValue(name)) {
            null -> null
            is LocalTime -> value
            is LocalDateTime -> value.toLocalTime()
            is ZonedDateTime -> value.toLocalTime()
            is OffsetDateTime -> value.toLocalTime()
            is Number -> Instant.ofEpochMilli(value.toLong()).atZone(ZoneId.systemDefault()).toLocalTime()
            is String -> {
                val number = value.toLongOrNull()
                if (number != null) {
                    Instant.ofEpochMilli(number).atZone(ZoneId.systemDefault()).toLocalTime()
                } else {
                    LocalTime.parse(value)
                }
            }
            else -> {
                throw IllegalArgumentException("Cannot convert ${value.javaClass.name} value to LocalTime.")
            }
        }
    }
}
