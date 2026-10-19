package com.winwasm64

import com.winwasm64.engine.DoomRaycasterState
import com.winwasm64.engine.Wasm64WindowsRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WindowsWasm64RuntimeTest {

    private fun createTempRuntime(): Wasm64WindowsRuntime {
        val tmpDir = Files.createTempDirectory("win_wasm64_test").toFile()
        return Wasm64WindowsRuntime(tmpDir)
    }

    @Test
    fun testWasm64ModuleCompilationAndMemory64Execution() {
        val rt = createTempRuntime()
        val wasmBytes = rt.assembleWasm64AddMulModule(a = 1000L, b = 2000L, c = 64L)
        val res = rt.executeWasm64Module(wasmBytes)

        assertTrue(res.validMagicHeader)
        assertTrue(res.memory64Addressing)
        assertEquals(192000L, res.returnValueI64)
        assertTrue(res.executedInstructions >= 7L)

        val bench = rt.runMultiCoreWasm64Benchmark()
        assertTrue(bench.validMagicHeader)
        assertTrue(bench.mips > 0L)
    }

    @Test
    fun testWindowsProgramsAndVirtualCDrive() {
        val rt = createTempRuntime()
        val kernelWasm = File(rt.system32Dir, "ntoskrnl.wasm")
        assertTrue(kernelWasm.isFile && kernelWasm.length() > 16L)

        val programs = rt.listWindowsPrograms()
        assertEquals(10, programs.size)
        assertTrue(programs.any { it.binaryName == "msedge.exe" })
        assertTrue(programs.any { it.binaryName == "youtube_app.exe" })
        assertTrue(programs.any { it.binaryName == "doom_wasm64.exe" })
        assertTrue(programs.any { it.binaryName == "winmine.exe" })
        assertTrue(programs.any { it.binaryName == "powershell.exe" })
    }

    @Test
    fun testWindowsGamesDoom3DRaycasterAndMinesweeper() {
        val rt = createTempRuntime()
        var doom = DoomRaycasterState()
        val walls = rt.castDoom3dWallHeights(doom, 32)
        assertEquals(32, walls.size)

        doom = rt.stepDoomRaycaster(doom, moveForward = 0.4, turnDelta = 0.15, fireWeapon = true)
        assertEquals(49, doom.ammo)
        assertEquals(3, doom.enemiesAlive)
        assertEquals(250, doom.score)

        var mines = rt.createNewMinesweeperGame(rows = 7, cols = 7, mines = 7)
        assertEquals(49, mines.cells.size)
        mines = rt.revealMinesweeperCell(mines, row = 0, col = 0, toggleFlag = false)
        assertFalse(mines.gameOver)
        assertEquals(1, mines.moves)
    }

    @Test
    fun testWindowsTerminalAndNodeJsRuntime() {
        val rt = createTempRuntime()

        val nodeVer = rt.executeTerminalOrNodeCommand("node -v")
        assertTrue(nodeVer.any { it.contains("v22.11.0-wasm64-win32") })

        val npmVer = rt.executeTerminalOrNodeCommand("npm -v")
        assertTrue(npmVer.any { it.contains("10.9.0") })

        val helloOut = rt.executeTerminalOrNodeCommand("node hello_node.js")
        assertTrue(helloOut.any { it.contains("Hello from Node.js on Windows 11 WASM64!") })
        assertTrue(helloOut.any { it.contains("11111111101110") })

        val evalOut = rt.executeNodeJsScript(
            """
            const a = 4096;
            const b = 8192;
            console.log("Sum64:", a + b);
            fs.writeFileSync("test_node.txt", "NodeJS WASM64 File OK");
            """.trimIndent()
        )
        assertTrue(evalOut.contains("Sum64: 12288"))
        assertTrue(File(rt.desktopDir, "test_node.txt").isFile)

        val npmInstall = rt.executeTerminalOrNodeCommand("npm install axios")
        assertTrue(npmInstall.any { it.contains("axios") })
    }

    @Test
    fun testYouTubeAndBrowserExtensionsAdBlockAndBrowsec() {
        val rt = createTempRuntime()

        assertTrue(rt.shouldBlockAdUrl("https://googleads.g.doubleclick.net/pagead/ads"))
        assertTrue(rt.shouldBlockAdUrl("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(rt.shouldBlockAdUrl("https://mc.yandex.ru/watch/12345"))
        assertFalse(rt.shouldBlockAdUrl("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(rt.shouldBlockAdUrl("https://github.com/sj0404-collab/windows-wasm64-apk"))

        assertEquals("https://youtube.com", rt.normalizeBrowserTarget("youtube.com"))
        assertTrue(rt.normalizeBrowserTarget("node js wasm64").startsWith("https://www.google.com/search?q="))
    }
}
