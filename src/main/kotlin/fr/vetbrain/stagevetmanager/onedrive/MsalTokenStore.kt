package fr.vetbrain.stagevetmanager.onedrive

import com.microsoft.aad.msal4j.ITokenCacheAccessAspect
import com.microsoft.aad.msal4j.ITokenCacheAccessContext
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

class MsalTokenStore : ITokenCacheAccessAspect {

    private val cachePath = Paths.get(
        System.getProperty("user.home"), ".stagevetmanager", "msal_cache.json"
    )

    override fun beforeCacheAccess(context: ITokenCacheAccessContext) {
        if (Files.exists(cachePath)) {
            runCatching {
                context.tokenCache().deserialize(Files.readString(cachePath))
            }
        }
    }

    override fun afterCacheAccess(context: ITokenCacheAccessContext) {
        if (!context.hasCacheChanged()) return
        runCatching {
            cachePath.parent.toFile().mkdirs()
            val tmp = Files.createTempFile(cachePath.parent, "msal_cache_", ".tmp")
            Files.writeString(tmp, context.tokenCache().serialize())
            // Restrict permissions before rename (POSIX only; no-op on Windows)
            runCatching {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
            }
            Files.move(tmp, cachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    fun deleteCacheFile() {
        runCatching { Files.deleteIfExists(cachePath) }
    }
}
