package com.github.cosmosdbclient.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionStringsTest {

    @Test fun parsesEndpointAndKeyWithBase64Padding() {
        val result = ConnectionStrings.parse("AccountEndpoint=https://a.documents.azure.com:443/;AccountKey=abc123==;")
        assertNotNull(result)
        assertEquals("https://a.documents.azure.com:443/", result!!.first)
        assertEquals("abc123==", result.second)
    }

    @Test fun keysAreCaseInsensitive() {
        val result = ConnectionStrings.parse("accountendpoint=https://x/;accountkey=k;")
        assertEquals("https://x/", result!!.first)
        assertEquals("k", result.second)
    }

    @Test fun returnsNullForPlainUri() {
        assertNull(ConnectionStrings.parse("https://a.documents.azure.com:443/"))
    }

    @Test fun missingKeyYieldsEmptyString() {
        val result = ConnectionStrings.parse("AccountEndpoint=https://x/;")
        assertEquals("https://x/", result!!.first)
        assertEquals("", result.second)
    }
}
