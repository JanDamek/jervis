package com.jervis.util

import com.jervis.entity.ClientDocument

object ClientSlugger {
    fun slugFor(client: ClientDocument): String {
        val base = client.name.lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .replace(Regex("-+"), "-")
        val shortId = client.id.toHexString().takeLast(4)
        return "$base-$shortId"
    }
}
