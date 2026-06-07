package com.github.cosmosdbclient.service

/** Parsing of Azure Cosmos DB connection strings, extracted for unit testing. */
object ConnectionStrings {

    /**
     * Parses `AccountEndpoint=...;AccountKey=...;` into (endpoint, key). The key may itself
     * contain `=` (base64 padding), so we split only on the first `=` of each segment.
     * Returns `null` if the text is not a connection string (no `AccountEndpoint`).
     */
    fun parse(text: String): Pair<String, String>? {
        if (!text.contains("AccountEndpoint=", ignoreCase = true)) return null
        val parts = text.split(";").mapNotNull { segment ->
            val i = segment.indexOf('=')
            if (i <= 0) null else segment.substring(0, i).trim() to segment.substring(i + 1).trim()
        }
        val endpoint = parts.firstOrNull { it.first.equals("AccountEndpoint", ignoreCase = true) }?.second ?: return null
        val key = parts.firstOrNull { it.first.equals("AccountKey", ignoreCase = true) }?.second ?: ""
        return endpoint to key
    }
}
