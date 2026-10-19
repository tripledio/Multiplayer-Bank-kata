package org.craftedsw.swift

import java.io.ByteArrayInputStream
import org.assertj.core.api.Assertions.assertThat
import org.craftedsw.swift.persistence.HubState
import org.junit.jupiter.api.Test

class SwiftHubApplicationTest {

    private val sampleState = HubState()

    @Test
    fun `shouldResume defaults to true when timeout occurs or no input is given`() {
        val result = shouldResume(sampleState, timeoutSeconds = 1L) { null }
        assertThat(result).isTrue()
    }

    @Test
    fun `shouldResume returns true when user enters yes or enter`() {
        assertThat(shouldResume(sampleState) { "y" }).isTrue()
        assertThat(shouldResume(sampleState) { "yes" }).isTrue()
        assertThat(shouldResume(sampleState) { "" }).isTrue()
    }

    @Test
    fun `shouldResume returns false when user enters no or new`() {
        assertThat(shouldResume(sampleState) { "n" }).isFalse()
        assertThat(shouldResume(sampleState) { "no" }).isFalse()
        assertThat(shouldResume(sampleState) { "new" }).isFalse()
    }

    @Test
    fun `readLineWithTimeout returns line when input is immediately available`() {
        val originalIn = System.`in`
        try {
            System.setIn(ByteArrayInputStream("n\n".toByteArray()))
            val answer = readLineWithTimeout(timeoutSeconds = 2L)
            assertThat(answer).isEqualTo("n")
        } finally {
            System.setIn(originalIn)
        }
    }
}
