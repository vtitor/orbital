package com.github.cosmosdbclient.model

/**
 * Non-secret description of a Cosmos DB account connection.
 * The account key is never stored here — it lives in the IDE [com.intellij.ide.passwordSafe.PasswordSafe]
 * and is looked up by [name].
 */
data class CosmosConnection(
    val name: String,
    val endpoint: String,
    val preferGateway: Boolean = true,
)
