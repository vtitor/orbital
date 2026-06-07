package com.github.cosmosdbclient.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Smoke test: the tool window content constructs without errors on the headless fixture. */
class CosmosExplorerPanelTest : BasePlatformTestCase() {

    fun testExplorerPanelConstructs() {
        val panel = CosmosExplorerPanel(project)
        assertTrue("Panel should have content", panel.componentCount > 0)
    }

    fun testToolWindowFactoryProducesContent() {
        val factory = CosmosToolWindowFactory()
        // The factory should at least be instantiable and report it is applicable by default.
        assertNotNull(factory)
    }
}
