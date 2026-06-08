package com.github.cosmosdbclient.service

import com.github.cosmosdbclient.model.CosmosConnection
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-level persistence for Cosmos DB connections.
 *
 * Connection metadata (name / endpoint / mode) is persisted via [PersistentStateComponent].
 * Account keys are kept separately in the secure [PasswordSafe], keyed by connection name.
 */
@Service(Service.Level.APP)
@State(name = "OrbitalConnections", storages = [Storage("orbital.xml")])
class CosmosConnectionStorage : PersistentStateComponent<CosmosConnectionStorage.State> {

    /** Persisted (non-secret) connection record. Mutable no-arg bean for XML serialization. */
    class StoredConnection {
        var name: String = ""
        var endpoint: String = ""
        var preferGateway: Boolean = true
    }

    class State {
        var connections: MutableList<StoredConnection> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(newState: State) {
        state = newState
    }

    // list/find/save/remove are @Synchronized: the connection list is read from background
    // threads (buildClient) while the EDT mutates it (save/remove), which would otherwise risk
    // a ConcurrentModificationException on the backing ArrayList.

    @Synchronized
    fun list(): List<CosmosConnection> =
        state.connections.map { CosmosConnection(it.name, it.endpoint, it.preferGateway) }

    @Synchronized
    fun find(name: String): CosmosConnection? =
        state.connections.firstOrNull { it.name == name }
            ?.let { CosmosConnection(it.name, it.endpoint, it.preferGateway) }

    /** Inserts or updates a connection (matched by [CosmosConnection.name]). A non-null [key] updates the stored key. */
    @Synchronized
    fun save(connection: CosmosConnection, key: String?) {
        val record = state.connections.firstOrNull { it.name == connection.name }
            ?: StoredConnection().also { state.connections.add(it) }
        record.name = connection.name
        record.endpoint = connection.endpoint
        record.preferGateway = connection.preferGateway
        if (key != null) setKey(connection.name, key)
    }

    @Synchronized
    fun remove(name: String) {
        state.connections.removeIf { it.name == name }
        setKey(name, null)
    }

    fun getKey(name: String): String? =
        PasswordSafe.instance.getPassword(credentialAttributes(name))

    private fun setKey(name: String, key: String?) {
        PasswordSafe.instance.set(credentialAttributes(name), key?.let { Credentials(name, it) })
    }

    private fun credentialAttributes(name: String): CredentialAttributes =
        CredentialAttributes(generateServiceName(SERVICE_NAME, name))

    companion object {
        private const val SERVICE_NAME = "Orbital"

        fun getInstance(): CosmosConnectionStorage = service()
    }
}
