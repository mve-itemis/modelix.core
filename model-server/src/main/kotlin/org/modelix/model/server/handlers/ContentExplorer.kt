package org.modelix.model.server.handlers

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.thymeleaf.*
import org.modelix.model.ModelFacade
import org.modelix.model.api.ITree
import org.modelix.model.api.PNodeAdapter
import org.modelix.model.api.TreePointer
import org.modelix.model.client.IModelClient
import org.modelix.model.lazy.CLVersion

class ContentExplorer(private val client: IModelClient, private val repoManager: RepositoriesManager) {

    private val rootNodes: List<PNodeAdapter>
        get() {
            val nodeList = mutableListOf<PNodeAdapter>()

            for (repoId in repoManager.getRepositories()) {
                val branchRef = repoId.getBranchReference()
                val version = ModelFacade.loadCurrentVersion(client, branchRef) ?: continue
                val rootNode = PNodeAdapter(ITree.ROOT_ID, TreePointer(version.getTree()))
                nodeList.add(rootNode)
            }
            return nodeList
        }

    private val allVersions: Set<CLVersion>
        get() {
            val versions = linkedSetOf<CLVersion>()
            for (repoId in repoManager.getRepositories()) {
                val hash = repoManager.getVersionHash(repoId.getBranchReference()) ?: continue
                val version = CLVersion(hash, client.storeCache)
                var current: CLVersion? = version
                while (current != null) {
                    versions.add(current)
                    current = current.baseVersion
                }
            }
            return versions
        }

    fun init(application: Application) {
        application.routing {
            get("/content/") {
                call.respond(ThymeleafContent("content_overview", mapOf("allVersions" to allVersions)))
            }
            get("/content/{versionHash}/") {
                val versionHash = call.parameters["versionHash"]
                if (versionHash.isNullOrEmpty()) {
                    call.respondText("version not found", status = HttpStatusCode.InternalServerError)
                    return@get
                }
                val tree = CLVersion.loadFromHash(versionHash, client.storeCache).getTree()
                val rootNode = PNodeAdapter(ITree.ROOT_ID, TreePointer(tree))
                call.respond(ThymeleafContent("content", mapOf("rootNodes" to listOf(rootNode))))
            }
            get("/content/{versionHash}/{nodeId}/") {
                val id = call.parameters["nodeId"]!!.toLong()
                var found: PNodeAdapter? = null
                for (node in rootNodes) {
                    val candidate = PNodeAdapter(id, node.branch).takeIf { it.isValid }
                    if (candidate != null) {
                        found = candidate
                        break
                    }
                }
                if (found == null) {
                    call.respondText("node id not found", status = HttpStatusCode.InternalServerError)
                } else {
                    call.respond(ThymeleafContent("fragments/node_inspector", mapOf("node" to found)))
                }
            }
        }
    }
}
