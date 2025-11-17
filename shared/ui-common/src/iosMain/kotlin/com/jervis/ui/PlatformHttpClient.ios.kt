package com.jervis.ui

import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.websocket.*

/**
 * iOS implementation of HTTP client with WebSocket support
 * NOTE: For self-signed certificates on iOS, you need to add the following to Info.plist:
 * <key>NSAppTransportSecurity</key>
 * <dict>
 *     <key>NSAllowsArbitraryLoads</key>
 *     <true/>
 * </dict>
 *
 * Or better yet, add exception for specific domain:
 * <key>NSAppTransportSecurity</key>
 * <dict>
 *     <key>NSExceptionDomains</key>
 *     <dict>
 *         <key>home.damek-soft.eu</key>
 *         <dict>
 *             <key>NSExceptionAllowsInsecureHTTPLoads</key>
 *             <true/>
 *             <key>NSIncludesSubdomains</key>
 *             <true/>
 *         </dict>
 *     </dict>
 * </dict>
 */
actual fun createPlatformHttpClient(): HttpClient {
    return HttpClient(Darwin) {
        engine {
            configureRequest {
                setAllowsCellularAccess(true)
            }
        }
        install(WebSockets) {
            pingIntervalMillis = 20_000
            maxFrameSize = Long.MAX_VALUE
        }
    }
}
