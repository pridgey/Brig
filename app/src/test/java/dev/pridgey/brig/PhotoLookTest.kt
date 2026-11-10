package dev.pridgey.brig

import dev.pridgey.brig.data.Appearance
import dev.pridgey.brig.data.BackgroundType
import dev.pridgey.brig.data.BrigSettings
import dev.pridgey.brig.data.PhotoConfig
import dev.pridgey.brig.data.PhotoEffect
import dev.pridgey.brig.data.PhotoLook
import dev.pridgey.brig.data.PhotoSourceConfig
import dev.pridgey.brig.data.SettingsRepository
import dev.pridgey.brig.data.withLookFor
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoLookTest {
    private val own = PhotoLook(PhotoConfig(effect = PhotoEffect.MONO, zoom = 1.5f), blur = 0.2f, dim = 0.5f)
    private val cfg = PhotoSourceConfig(albumPaths = listOf("a", "b", "c"), looks = mapOf("b" to own))

    @Test
    fun ownLookAppliesOnlyToItsPhoto() {
        val a = Appearance(background = BackgroundType.IMAGE, dim = 0.1f)
        val b = a.withLookFor(cfg, "b")
        assertEquals(PhotoEffect.MONO, b.photo.effect)
        assertEquals(0.5f, b.dim)
        assertEquals(0.2f, b.blur)
        assertEquals(a, a.withLookFor(cfg, "a"))
        assertEquals(a, a.withLookFor(cfg, null))
    }

    @Test
    fun ownLookIgnoredWhenBackgroundIsNotAPhoto() {
        val a = Appearance(background = BackgroundType.AURORA)
        assertEquals(a, a.withLookFor(cfg, "b"))
    }

    @Test
    fun removingPhotosDropsTheirLooks() {
        val next = cfg.without(listOf("b", "c"))
        assertEquals(listOf("a"), next.albumPaths)
        assertEquals(emptyMap<String, PhotoLook>(), next.looks)
    }

    @Test
    fun looksSurviveSaveAndOldSettingsStillLoad() {
        val s = BrigSettings(photos = cfg)
        val json = SettingsRepository.json.encodeToString(BrigSettings.serializer(), s)
        assertEquals(cfg, SettingsRepository.decode(json).photos)
        val old = """{"photos":{"source":"ALBUM","albumPaths":["x"]}}"""
        assertEquals(emptyMap<String, PhotoLook>(), SettingsRepository.decode(old).photos.looks)
    }
}
