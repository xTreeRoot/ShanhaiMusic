package com.xtreeroot.mb.local

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.preferences.AppearanceBackgroundStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceBackgroundDeviceTest {
    @Test fun restoreCopiesOnlyBoundedPngAndPreservesAnExistingBackground() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(app.cacheDir.toPath(), "appearance-test-").toFile()
        val store = AppearanceBackgroundStore(object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
        })
        try {
            val oversized = File(directory, "oversized.png").apply { png(1601, Color.BLUE) }
            assertTrue(runCatching { store.restoreIfMissing(oversized) }.exceptionOrNull() is IllegalArgumentException)
            assertNull(store.load())
            val original = File(directory, "original.png").apply { png(80, Color.RED) }
            store.restoreIfMissing(original)
            assertArrayEquals(original.readBytes(), store.snapshot { file, _ -> file.readBytes() })
            val restored = checkNotNull(store.load())
            assertEquals(Color.RED, restored.getPixel(0, 0))
            restored.recycle()
            val second = File(directory, "second.png").apply { png(80, Color.GREEN) }
            store.restoreIfMissing(second)
            val candidate = checkNotNull(android.graphics.BitmapFactory.decodeFile(second.path))
            // 只解码/裁剪候选图时原背景不动，save 才提交。
            assertEquals(Color.GREEN, candidate.getPixel(0, 0))
            assertArrayEquals(original.readBytes(), store.snapshot { file, _ -> file.readBytes() })
            store.save(candidate)
            assertEquals(Color.GREEN, checkNotNull(store.load()).getPixel(0, 0))
            assertTrue(runCatching { store.prepareRemote("file:///invalid.png") }.isFailure)
            assertEquals(Color.GREEN, checkNotNull(store.load()).getPixel(0, 0))
            candidate.recycle()
            store.clear()
            assertNull(store.snapshot { file, _ -> file.readBytes() })
        } finally { directory.deleteRecursively() }
    }

    private fun File.png(width: Int, color: Int) {
        val bitmap = Bitmap.createBitmap(width, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
