package com.ota.desktop.update

import java.io.File

/**
 * Installs a downloaded DMG after the app has exited, then starts the app again. A small shell
 * script does it: wait for this process to end → mount the DMG read-only → copy the new
 * `OTA Business.app` next to the running one → swap the two bundles with renames (the old bundle
 * comes back if the swap fails) → unmount → write the exit code → `open` the app (the new version,
 * or the old one when anything failed). The data folder (~/Library/Application Support/OTA Business
 * Data) is outside the bundle, so it is never touched.
 *
 * Exit codes: 0 ok, 2 the DMG would not mount or holds no app, 3 copying failed, 4 the swap failed.
 */
object MacInstaller {
    /**
     * The running `…/OTA Business.app`, when started from an installed bundle that can be replaced:
     * not straight from the mounted DMG, and not a copy macOS runs from a read-only quarantine
     * location (App Translocation, until the app is moved to Applications).
     */
    fun appBundle(): File? {
        val candidates = listOfNotNull(System.getProperty("jpackage.app-path"), ProcessHandle.current().info().command().orElse(null))
        val bundle = candidates.asSequence().map { generateSequence(File(it).absoluteFile) { f -> f.parentFile } }
            .mapNotNull { chain -> chain.firstOrNull { it.name.endsWith(".app") && it.isDirectory } }
            .firstOrNull() ?: return null
        return bundle.takeUnless { replaceableIssue(bundle.path) != null }
    }

    /** Why a bundle at [path] can't be updated in place, or null when it can. */
    fun replaceableIssue(path: String): String? = when {
        path.contains("/AppTranslocation/") -> "macOS is running OTA Business from a temporary copy"
        path.startsWith("/Volumes/") -> "OTA Business is running from the installer disk image"
        else -> null
    }

    /** Readable reasons for the script's exit codes. */
    fun reason(code: Int): String? = when (code) {
        2 -> "the downloaded update could not be opened"
        3 -> "copying the new version failed"
        4 -> "the app could not be replaced; move OTA Business to the Applications folder and try again"
        else -> null
    }

    fun script(dmg: File, log: File, result: File, pid: Long, bundle: File?, args: List<String>): String {
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val relaunchArgs = if (args.isEmpty()) "" else " --args " + args.joinToString(" ") { q(it) }
        val target = bundle?.absolutePath ?: ""
        return """
            |#!/bin/bash
            |exec >>${q(log.absolutePath)} 2>&1
            |echo "update started ${'$'}(date)"
            |app=${q(target)}
            |deadline=${'$'}((SECONDS + 180))
            |while kill -0 $pid 2>/dev/null && [ ${'$'}SECONDS -lt ${'$'}deadline ]; do sleep 0.5; done
            |kill -9 $pid 2>/dev/null
            |sleep 1
            |code=2
            |mnt=${'$'}(mktemp -d /tmp/ota-update.XXXXXX)
            |if [ -n "${'$'}app" ] && hdiutil attach -nobrowse -readonly -noautoopen -mountpoint "${'$'}mnt" ${q(dmg.absolutePath)}; then
            |  src=${'$'}(find "${'$'}mnt" -maxdepth 1 -name '*.app' -type d | head -n 1)
            |  if [ -n "${'$'}src" ]; then
            |    new="${'$'}app.new"; old="${'$'}app.old"
            |    rm -rf "${'$'}new" "${'$'}old"
            |    if ditto "${'$'}src" "${'$'}new"; then
            |      if mv "${'$'}app" "${'$'}old" && mv "${'$'}new" "${'$'}app"; then
            |        rm -rf "${'$'}old"; code=0
            |      else
            |        [ ! -d "${'$'}app" ] && [ -d "${'$'}old" ] && mv "${'$'}old" "${'$'}app"
            |        rm -rf "${'$'}new"; code=4
            |      fi
            |    else
            |      rm -rf "${'$'}new"; code=3
            |    fi
            |  fi
            |  hdiutil detach "${'$'}mnt" -quiet || hdiutil detach "${'$'}mnt" -force -quiet
            |fi
            |rmdir "${'$'}mnt" 2>/dev/null
            |echo "${'$'}code" > ${q(result.absolutePath)}
            |echo "update finished with ${'$'}code"
            |[ -n "${'$'}app" ] && open -n "${'$'}app"$relaunchArgs
            |""".trimMargin()
    }

    /** Writes the helper next to the DMG and starts it detached; the caller must exit right after. */
    fun launch(dmg: File, log: File, result: File, args: List<String>) {
        val bundle = appBundle() ?: throw IllegalStateException("OTA Business is not running from an installed app")
        val script = File(dmg.parentFile, "install-update.sh")
        result.delete()
        script.writeText(script(dmg, log, result, ProcessHandle.current().pid(), bundle, args))
        script.setExecutable(true)
        // nohup: the helper must outlive this process, which exits as soon as it has started.
        ProcessBuilder("/usr/bin/nohup", "/bin/bash", script.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(File(dmg.parentFile, "install-update.out.txt"))
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .start()
    }
}
