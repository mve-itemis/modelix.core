/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.modelix.model.server.handlers

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import org.json.JSONArray
import org.json.JSONObject
import org.modelix.authorization.*
import org.modelix.model.persistent.HashUtil
import org.modelix.model.server.store.IStoreClient
import org.modelix.model.server.store.pollEntry
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*
import java.util.regex.Pattern

val PERMISSION_MODEL_SERVER = "model-server".asResource()
val MODEL_SERVER_ENTRY = KeycloakResourceType("model-server-entry", KeycloakScope.READ_WRITE_DELETE)

private fun toLong(value: String?): Long {
    return if (value == null || value.isEmpty()) 0 else value.toLong()
}

private class NotFoundException(description: String?) : RuntimeException(description)

typealias CallContext = PipelineContext<Unit, ApplicationCall>

class KeyValueLikeModelServer(val storeClient: IStoreClient) {
    companion object {
        private val LOG = LoggerFactory.getLogger(KeyValueLikeModelServer::class.java)
        val HASH_PATTERN = Pattern.compile("[a-zA-Z0-9\\-_]{5}\\*[a-zA-Z0-9\\-_]{38}")
        const val PROTECTED_PREFIX = "$$$"
        val HEALTH_KEY = PROTECTED_PREFIX + "health2"
        private const val LEGACY_SERVER_ID_KEY = "repositoryId"
        private const val SERVER_ID_KEY = "server-id"

        private fun randomUUID(): String {
            return UUID.randomUUID().toString().replace("[^a-zA-Z0-9]".toRegex(), "")
        }

        fun initServerId(storeClient: IStoreClient) {
            storeClient.runTransaction {
                var serverId = storeClient[SERVER_ID_KEY]
                if (serverId == null) {
                    serverId = storeClient[LEGACY_SERVER_ID_KEY] ?: randomUUID()
                    storeClient.put(SERVER_ID_KEY, serverId)
                    storeClient.put(LEGACY_SERVER_ID_KEY, serverId)
                }
            }
        }

        fun getServerId(storeClient: IStoreClient): String {
            return storeClient[SERVER_ID_KEY]!!
        }
    }

    fun init(application: Application) {
        initServerId(storeClient)
        application.apply {
            modelServerModule()
        }
    }

    private fun Application.modelServerModule() {

        routing {
            get("/health") {
                if (isHealthy()) {
                    call.respondText(text = "healthy", contentType = ContentType.Text.Plain, status = HttpStatusCode.OK)
                } else {
                    call.respondText(text = "not healthy", contentType = ContentType.Text.Plain, status = HttpStatusCode.InternalServerError)
                }
            }
            get("/headers") {
                val headers = call.request.headers.entries().flatMap { e -> e.value.map { e.key to it } }
                call.respondText(headers.joinToString("\n") { "${it.first}: ${it.second}" })
            }
            requiresPermission(PERMISSION_MODEL_SERVER, EPermissionType.READ) {
                get("/get/{key}") {
                    val key = call.parameters["key"]!!
                    checkKeyPermission(key, EPermissionType.READ)
                    val value = storeClient[key]
                    respondValue(key, value)
                }

                get("/poll/{key}") {
                    val key: String = call.parameters["key"]!!
                    val lastKnownValue = call.request.queryParameters["lastKnownValue"]
                    checkKeyPermission(key, EPermissionType.READ)
                    val newValue = pollEntry(storeClient, key, lastKnownValue)
                    respondValue(key, newValue)
                }

                get("/getEmail") {
                    call.respondText(call.getUserName() ?: "<no email>")
                }

                post("/counter/{key}") {
                    val key = call.parameters["key"]!!
                    checkKeyPermission(key, EPermissionType.WRITE)
                    val value = storeClient.generateId(key)
                    call.respondText(text = value.toString())
                }

                get("/getRecursively/{key}") {
                    val key = call.parameters["key"]!!
                    call.respondText(collect(key).toString(2), contentType = ContentType.Application.Json)
                }

                put("/put/{key}") {
                    val key = call.parameters["key"]!!
                    val value = call.receiveText()
                    try {
                        putEntries(mapOf(key to value))
                        call.respondText("OK")
                    } catch (e: NotFoundException) {
                        call.respondText(e.message ?: "Not found", status = HttpStatusCode.NotFound)
                    }
                }

                put("/putAll") {
                    val jsonStr = call.receiveText()
                    val json = JSONArray(jsonStr)
                    var entries: MutableMap<String, String?> = LinkedHashMap()
                    for (entry_ in json) {
                        val entry = entry_ as JSONObject
                        val key = entry.getString("key")
                        val value = entry.optString("value", null)
                        entries[key] = value
                    }
                    entries = sortByDependency(entries)
                    try {
                        putEntries(entries)
                        call.respondText(entries.size.toString() + " entries written")
                    } catch (e: NotFoundException) {
                        call.respondText(e.message ?: "Not found", status = HttpStatusCode.NotFound)
                    }
                }

                put("/getAll") {
                    // PUT is used, because a GET is not allowed to have a request body that changes the result of the
                    // request. It would be legal for an HTTP proxy to cache all /getAll requests and ignore the body.
                    val reqJsonStr = call.receiveText()
                    val reqJson = JSONArray(reqJsonStr)
                    val respJson = JSONArray()
                    val keys: MutableList<String> = ArrayList(reqJson.length())
                    for (entry_ in reqJson) {
                        val key = entry_ as String
                        checkKeyPermission(key, EPermissionType.READ)
                        keys.add(key)
                    }
                    val values = storeClient.getAll(keys)
                    for (i in keys.indices) {
                        val respEntry = JSONObject()
                        respEntry.put("key", keys[i])
                        respEntry.put("value", values[i])
                        respJson.put(respEntry)
                    }
                    call.respondText(respJson.toString(), contentType = ContentType.Application.Json)
                }
            }
            requiresPermission(PERMISSION_MODEL_SERVER, EPermissionType.WRITE) {

            }
        }
    }

    private var sharedSecret: String? = null
    fun setSharedSecret(sharedSecret: String?) {
        this.sharedSecret = sharedSecret
    }

    private fun sortByDependency(unsorted: Map<String, String?>): MutableMap<String, String?> {
        val sorted: MutableMap<String, String?> = LinkedHashMap()
        val processed: MutableSet<String> = HashSet()
        object : Any() {
            fun fill(key: String) {
                if (processed.contains(key)) return
                processed.add(key)
                val value = unsorted[key]
                for (referencedKey in extractHashes(value)) {
                    if (unsorted.containsKey(referencedKey)) fill(referencedKey)
                }
                sorted[key] = value
            }

            fun fill() {
                for (key in unsorted.keys) {
                    fill(key)
                }
            }
        }.fill()
        return sorted
    }

    fun collect(rootKey: String): JSONArray {
        val result = JSONArray()
        val processed: MutableSet<String> = HashSet()
        val pending: MutableSet<String> = HashSet()
        pending.add(rootKey)
        while (!pending.isEmpty()) {
            val keys: List<String> = ArrayList(pending)
            pending.clear()
            val values = storeClient.getAll(keys)
            for (i in keys.indices) {
                val key = keys[i]
                val value = values[i]
                processed.add(key)
                val entry = JSONObject()
                entry.put("key", key)
                entry.put("value", value)
                result.put(entry)
                for (foundKey in extractHashes(value)) {
                    if (!processed.contains(foundKey)) {
                        pending.add(foundKey)
                    }
                }
            }
        }
        return result
    }

    private fun extractHashes(input: String?): List<String> {
        val result: MutableList<String> = ArrayList()
        if (input != null) {
            val matcher = HASH_PATTERN.matcher(input)
            while (matcher.find()) {
                result.add(matcher.group())
            }
        }
        return result
    }

    protected fun CallContext.putEntries(newEntries: Map<String, String?>) {
        val referencedKeys: MutableSet<String> = HashSet()
        for ((key, value) in newEntries) {
            checkKeyPermission(key, EPermissionType.WRITE)
            if (value != null) {
                val matcher = HASH_PATTERN.matcher(value)
                while (matcher.find()) {
                    val foundKey = matcher.group()
                    if (!newEntries.containsKey(foundKey)) {
                        referencedKeys.add(foundKey)
                    }
                }
            }
        }
        val referencedEntries = storeClient.getAll(referencedKeys)
        for (key in referencedKeys) {
            if (referencedEntries[key] == null) {
                throw NotFoundException("Referenced key $key not found")
            }
        }
        storeClient.putAll(newEntries)
    }

    private suspend fun CallContext.respondValue(key: String, value: String?) {
        if (value == null) {
            call.respondText("key '$key' not found", status = HttpStatusCode.NotFound)
        } else {
            call.respondText(text = value, contentType = ContentType.Text.Plain)
        }
    }

    @Throws(IOException::class)
    private fun CallContext.checkKeyPermission(key: String, type: EPermissionType) {
        if (key.startsWith(PROTECTED_PREFIX)) {
            throw NoPermissionException("Access to keys starting with '$PROTECTED_PREFIX' is only permitted to the model server itself.")
        }
        if (key.startsWith(RepositoriesManager.KEY_PREFIX)) {
            throw NoPermissionException("Access to keys starting with '${RepositoriesManager.KEY_PREFIX}' is only permitted to the model server itself.")
        }
        if ((key == SERVER_ID_KEY || key == LEGACY_SERVER_ID_KEY) && type.includes(EPermissionType.WRITE)) {
            throw NoPermissionException("'$key' is read-only.")
        }
        if (HashUtil.isSha256(key)) {
            // Reading entries with a hash key is equivalent to uncompressing data that the user already has access to.
            // If he isn't allowed to read the entry then he shouldn't be allowed to know the hash.
            // A permission check has happened somewhere earlier.
            return
        }
        call.checkPermission(MODEL_SERVER_ENTRY.createInstance(key), type.toKeycloakScope())
    }



    fun isHealthy(): Boolean {
        val value = toLong(storeClient[HEALTH_KEY]) + 1
        storeClient.put(HEALTH_KEY, java.lang.Long.toString(value),)
        return toLong(storeClient[HEALTH_KEY]) >= value
    }
}