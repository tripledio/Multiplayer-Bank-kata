package org.craftedsw.bank.domain

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

@JvmInline
value class Amount(val value: Int) {

    fun plus(otherAmount: Amount): Amount = Amount(this.value + otherAmount.value)

    fun isGreaterThan(otherAmount: Amount): Boolean = this.value > otherAmount.value

    fun absoluteValue(): Amount = Amount(abs(value))

    fun moneyRepresentation(): String = DecimalFormat("#.00", DecimalFormatSymbols(Locale.US)).format(value)

    fun negative(): Amount = Amount(-value)

    companion object {
        fun amountOf(value: Int): Amount = Amount(value)
    }
}
