package org.craftedsw.bank.debug

import java.util.concurrent.ConcurrentLinkedDeque
import org.craftedsw.contracts.DebugReport

class DebugStore(private val maxEntries: Int = 100) {
    private val reports = ConcurrentLinkedDeque<DebugReport>()

    fun add(report: DebugReport) {
        reports.addFirst(report)
        while (reports.size > maxEntries) {
            reports.pollLast()
        }
    }

    fun getAll(): List<DebugReport> = reports.toList()

    fun clear() {
        reports.clear()
    }

    val size: Int
        get() = reports.size
}
