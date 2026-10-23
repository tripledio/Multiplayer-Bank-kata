package org.craftedsw.bank.debug

import org.assertj.core.api.Assertions.assertThat
import org.craftedsw.contracts.DebugReport
import org.junit.jupiter.api.Test

class DebugStoreTest {

    @Test
    fun `should store debug reports and retrieve in reverse chronological order`() {
        val store = DebugStore(maxEntries = 10)
        val report1 = DebugReport(id = "1", scenario = "DEPOSIT_50_EUR", message = "Deposit failed", httpRequest = "POST /api/deposit", expectedResponse = "200", actualResponse = "400")
        val report2 = DebugReport(id = "2", scenario = "WITHDRAW_HAPPY_20_EUR", message = "Withdraw failed", httpRequest = "POST /api/withdraw", expectedResponse = "200", actualResponse = "500")

        store.add(report1)
        store.add(report2)

        assertThat(store.size).isEqualTo(2)
        val all = store.getAll()
        assertThat(all).hasSize(2)
        assertThat(all[0].id).isEqualTo("2")
        assertThat(all[1].id).isEqualTo("1")
    }

    @Test
    fun `should cap store size to maxEntries`() {
        val store = DebugStore(maxEntries = 3)
        for (i in 1..5) {
            store.add(DebugReport(id = "$i", scenario = "TEST", message = "Msg $i", httpRequest = "", expectedResponse = "", actualResponse = ""))
        }

        assertThat(store.size).isEqualTo(3)
        val ids = store.getAll().map { it.id }
        assertThat(ids).containsExactly("5", "4", "3")
    }

    @Test
    fun `should clear store`() {
        val store = DebugStore()
        store.add(DebugReport(id = "1", scenario = "TEST", message = "", httpRequest = "", expectedResponse = "", actualResponse = ""))
        assertThat(store.size).isEqualTo(1)

        store.clear()
        assertThat(store.size).isEqualTo(0)
        assertThat(store.getAll()).isEmpty()
    }
}
