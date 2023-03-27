package org.modelix.model.server.handlers

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.thymeleaf.*
import org.modelix.model.ModelFacade
import org.modelix.model.api.*
import org.modelix.model.client.IModelClient
import org.modelix.model.lazy.CLVersion
import org.modelix.model.lazy.RepositoryId
import org.modelix.model.metameta.MetaModelBranch

class ContentExplorer(private val client: IModelClient) {

    private val knownRepositoryIds: Set<HistoryHandler.RepositoryAndBranch>
        get() {
            val result: MutableSet<HistoryHandler.RepositoryAndBranch> = HashSet()
            val infoVersionHash = client[RepositoryId("info").getBranchReference().getKey()] ?: return emptySet()
            val infoVersion = CLVersion(infoVersionHash, client.storeCache)
            val infoBranch: IBranch = MetaModelBranch(PBranch(infoVersion.tree, IdGeneratorDummy()))
            infoBranch.runReadT { t: IReadTransaction ->
                for (infoNodeId in t.getChildren(ITree.ROOT_ID, "info")) {
                    for (repositoryNodeId in t.getChildren(infoNodeId, "repositories")) {
                        val repositoryId = t.getProperty(repositoryNodeId, "id") ?: continue
                        result.add(HistoryHandler.RepositoryAndBranch(repositoryId))
                        for (branchNodeId in t.getChildren(repositoryNodeId, "branches")) {
                            val branchName = t.getProperty(branchNodeId, "name") ?: continue
                            result.add(HistoryHandler.RepositoryAndBranch(repositoryId, branchName))
                        }
                    }
                }
            }
            return result
        }

    private val rootNodes: List<PNodeAdapter>
        get() {
            val nodeList = mutableListOf<PNodeAdapter>()

            for (repoID in knownRepositoryIds) {
                val branchRef = RepositoryId(repoID.repository).getBranchReference()
                val version = ModelFacade.loadCurrentVersion(client, branchRef) ?: continue
                val rootNode = PNodeAdapter(ITree.ROOT_ID, TreePointer(version.tree))
                nodeList.add(rootNode)
            }
            return nodeList
        }

    fun init(application: Application) {
        application.routing {
            get("/content/") {
                call.respond(ThymeleafContent("content", mapOf("rootNodes" to rootNodes)))
            }
            get("/content/{nodeId}/") {
                val id = call.parameters["nodeId"]!!.toLong()
                var found: PNodeAdapter? = null
                for (node in rootNodes) {
                    val candidate = node.findChildRec(id)
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

    private fun PNodeAdapter.findChildRec(nodeId: Long) : PNodeAdapter? {
        if (this.nodeId == nodeId)
            return this

        for (child in allChildren) {
            val node = (child as PNodeAdapter).findChildRec(nodeId)
            if (node != null) {
                return node
            }
        }
        return null
    }
}
