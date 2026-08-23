package dev.noemt.client.utils

import java.util.TreeMap
import kotlin.math.pow
import kotlin.math.roundToInt

object NumbersUtils {
    private val romanNumbers = TreeMap<Char, Int>()

    init {
        romanNumbers['m'] = 1000
        romanNumbers['d'] = 500
        romanNumbers['c'] = 100
        romanNumbers['l'] = 50
        romanNumbers['x'] = 10
        romanNumbers['v'] = 5
        romanNumbers['i'] = 1
    }

    fun Double.toFixed(precision: Int): String {
        if (this.isNaN()) return toString()
        val scale = 10.0.pow(precision).toInt()
        val rounded = (this * scale).roundToInt().toDouble() / scale
        val parts = rounded.toString().split(".")

        return if (parts.size == 2) {
            val decimals = parts[1].padEnd(precision, '0')
            "${parts[0]}.$decimals"
        } else {
            "${parts[0]}." + "0".repeat(precision)
        }
    }

    fun Float.toFixed(precision: Int): String = toDouble().toFixed(precision)

    fun String.romanToDecimal(): Int {
        var lastValue = 0
        var decimal = 0

        for (i in lastIndex downTo 0) {
            val value = romanNumbers[get(i).lowercaseChar()] ?: continue
            decimal += if (value < lastValue) -value else value
            lastValue = value
        }

        return decimal
    }

    operator fun Number.div(number: Number) = toDouble() / number.toDouble()
    operator fun Number.times(number: Number) = toDouble() * number.toDouble()
    operator fun Number.minus(number: Number) = toDouble() - number.toDouble()
    operator fun Number.plus(number: Number) = toDouble() + number.toDouble()
}
