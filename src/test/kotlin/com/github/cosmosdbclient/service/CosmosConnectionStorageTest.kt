package com.github.cosmosdbclient.service

import com.github.cosmosdbclient.model.CosmosConnection
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Platform tests: persistence + secure key storage run on the headless IDE test fixture. */
class CosmosConnectionStorageTest : BasePlatformTestCase() {

    private lateinit var storage: CosmosConnectionStorage

    override fun setUp() {
        super.setUp()
        storage = CosmosConnectionStorage.getInstance()
        storage.list().forEach { storage.remove(it.name) }
    }

    override fun tearDown() {
        try {
            storage.list().forEach { storage.remove(it.name) }
        } finally {
            super.tearDown()
        }
    }

    fun testSaveFindAndKey() {
        storage.save(CosmosConnection("acc", "https://a.documents.azure.com:443/", true), "secret")
        val found = storage.find("acc")
        assertNotNull(found)
        assertEquals("https://a.documents.azure.com:443/", found!!.endpoint)
        assertTrue(found.preferGateway)
        assertEquals("secret", storage.getKey("acc"))
    }

    fun testUpdateExistingDoesNotDuplicate() {
        storage.save(CosmosConnection("acc", "https://a", true), "k1")
        storage.save(CosmosConnection("acc", "https://b", false), "k2")
        assertEquals(1, storage.list().count { it.name == "acc" })
        val found = storage.find("acc")!!
        assertEquals("https://b", found.endpoint)
        assertFalse(found.preferGateway)
        assertEquals("k2", storage.getKey("acc"))
    }

    fun testRemoveClearsConnectionAndKey() {
        storage.save(CosmosConnection("acc", "https://a", true), "k")
        storage.remove("acc")
        assertNull(storage.find("acc"))
        assertNull(storage.getKey("acc"))
    }

    fun testSaveWithNullKeyKeepsExistingKey() {
        storage.save(CosmosConnection("acc", "https://a", true), "k")
        storage.save(CosmosConnection("acc", "https://a2", true), null)
        assertEquals("k", storage.getKey("acc"))
        assertEquals("https://a2", storage.find("acc")!!.endpoint)
    }

    fun testServicesAndNotificationGroupAreRegistered() {
        assertNotNull(service<CosmosService>())
        assertNotNull(service<CosmosConnectionStorage>())
        assertNotNull(
            "Notification group must be registered in plugin.xml",
            NotificationGroupManager.getInstance().getNotificationGroup(CosmosErrors.GROUP_ID),
        )
    }
}
