package fr.vetbrain.stagevetmanager.onedrive

import com.microsoft.aad.msal4j.DeviceCodeFlowParameters
import com.microsoft.aad.msal4j.InteractiveRequestParameters
import com.microsoft.aad.msal4j.PublicClientApplication
import com.microsoft.aad.msal4j.SilentParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

class OneDriveAuthClient(clientId: String) {

    private val scopes = setOf("Files.ReadWrite", "offline_access")
    private val tokenStore = MsalTokenStore()

    private val pca: PublicClientApplication = PublicClientApplication
        .builder(clientId)
        .authority("https://login.microsoftonline.com/common")
        .setTokenCacheAccessAspect(tokenStore)
        .build()

    suspend fun acquireToken(onDeviceCode: (String) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            // 1. Tentative silencieuse (refresh token en cache)
            val accounts = pca.accounts.get()
            if (accounts.isNotEmpty()) {
                val silent = SilentParameters.builder(scopes, accounts.first()).build()
                runCatching { pca.acquireTokenSilently(silent).get().accessToken() }
                    .getOrNull()?.let { return@withContext it }
            }

            // 2. Interactif : navigateur système (Desktop disponible sur Windows/macOS/Linux GUI)
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                val params = InteractiveRequestParameters
                    .builder(URI("http://localhost"))
                    .scopes(scopes)
                    .build()
                return@withContext pca.acquireToken(params).get().accessToken()
            }

            // 3. Fallback device-code (Linux headless, pas de navigateur)
            val params = DeviceCodeFlowParameters
                .builder(scopes) { challenge ->
                    onDeviceCode(challenge.message())
                }
                .build()
            pca.acquireToken(params).get().accessToken()
        }

    fun signOut() {
        runCatching {
            pca.accounts.get().forEach { pca.removeAccount(it).get() }
        }
        tokenStore.deleteCacheFile()
    }

    fun hasAccount(): Boolean =
        runCatching { pca.accounts.get().isNotEmpty() }.getOrDefault(false)
}
