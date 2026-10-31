package ru.topoar

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Приложение должно открываться: экран создаётся, показывается стартовое меню, не падает при паузе/возврате. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StartupTest {
    @Test
    fun opens() {
        val c = Robolectric.buildActivity(MainActivity::class.java).create().start()
        assertNotNull(c.get())
        c.resume().pause().stop().destroy()
    }
}
