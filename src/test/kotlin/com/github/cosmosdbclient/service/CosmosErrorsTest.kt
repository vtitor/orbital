package com.github.cosmosdbclient.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CosmosErrorsTest {

    @Test fun shortMessageTakesFirstLine() {
        assertEquals("line one", CosmosErrors.shortMessage(RuntimeException("line one\nline two")))
    }

    @Test fun shortMessageTrimsDiagnosticsBlob() {
        val message = """Resource Not Found, {"diagnostics":"... a very long blob ..."}"""
        assertEquals("Resource Not Found", CosmosErrors.shortMessage(RuntimeException(message)))
    }

    @Test fun shortMessageFallsBackToClassNameWhenNoMessage() {
        assertEquals("IllegalStateException", CosmosErrors.shortMessage(IllegalStateException()))
    }

    @Test fun detailsContainsContextTypeAndMessage() {
        val text = CosmosErrors.details("Query", RuntimeException("boom"))
        assertTrue(text.contains("Operation: Query"))
        assertTrue(text.contains("RuntimeException"))
        assertTrue(text.contains("boom"))
    }
}
