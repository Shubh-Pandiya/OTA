package com.ota.desktop.update

import java.io.File

/**
 * Runs a downloaded MSI after the app has exited, then starts the app again. A small PowerShell
 * script does it, because Windows cannot replace the app's files while it runs:
 * wait for this process to end → `msiexec /i … /passive` (per-user, no admin prompt) → write
 * msiexec's exit code → start `OTA Business.exe` (the new version, or the old one if the install
 * failed or was cancelled; Windows Installer rolls a failed upgrade back by itself).
 */
object WindowsInstaller {
    /** The installed launcher (`…\OTA Business\OTA Business.exe`), when running from an install. */
    fun appExe(): File? {
        val candidates = listOfNotNull(System.getProperty("jpackage.app-path"), ProcessHandle.current().info().command().orElse(null))
        return candidates.map(::File).firstOrNull { it.isFile && it.name.endsWith(".exe", ignoreCase = true) && !it.name.startsWith("java", ignoreCase = true) }
    }

    fun script(msi: File, log: File, result: File, pid: Long, exe: File?, args: List<String>): String {
        fun lit(s: String) = "'" + s.replace("'", "''") + "'"
        fun q(s: String) = "\"" + s.replace("\"", "") + "\""
        val relaunch = exe?.let {
            val list = if (args.isEmpty()) "" else " -ArgumentList " + lit(args.joinToString(" ") { a -> q(a) })
            "Start-Process -FilePath ${lit(it.absolutePath)}$list"
        } ?: "# not running from an installed copy: nothing to restart"
        return """
            |${'$'}ErrorActionPreference = 'Continue'
            |${'$'}deadline = (Get-Date).AddMinutes(3)
            |while ((Get-Process -Id $pid -ErrorAction SilentlyContinue) -and (Get-Date) -lt ${'$'}deadline) { Start-Sleep -Milliseconds 500 }
            |Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
            |Start-Sleep -Seconds 1
            |${'$'}code = 1603
            |try {
            |  ${'$'}p = Start-Process -FilePath 'msiexec.exe' -ArgumentList ${lit("/i ${q(msi.absolutePath)} /passive /norestart /l*v ${q(log.absolutePath)}")} -Wait -PassThru
            |  ${'$'}code = ${'$'}p.ExitCode
            |} catch { ${'$'}_ | Out-File -FilePath ${lit(log.absolutePath + ".error.txt")} }
            |Set-Content -Path ${lit(result.absolutePath)} -Value ${'$'}code -Encoding ascii
            |$relaunch
            |""".trimMargin()
    }

    /** Writes the helper next to the installer and starts it; the caller must exit right after. */
    fun launch(msi: File, log: File, result: File, args: List<String>) {
        val exe = appExe()
        val script = File(msi.parentFile, "install-update.ps1")
        result.delete()
        script.writeText(script(msi, log, result, ProcessHandle.current().pid(), exe, args))
        ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(File(msi.parentFile, "install-update.out.txt"))
            .start()
    }
}
