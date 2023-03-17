package org.modelix.model.server

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
import java.io.File

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

    fun init(application: Application) {
        application.routing {
            get("/content/") {
                val model = mutableMapOf<String, Any>()
                val nodeList = mutableListOf<INode>()

                for (repoID in knownRepositoryIds) {
                    val branchRef = RepositoryId(repoID.repository).getBranchReference()
                    val version = ModelFacade.loadCurrentVersion(client, branchRef)
                    if (version == null) {
                        call.respond(HttpStatusCode.InternalServerError)
                        return@get
                    }

                    val rootNode = PNodeAdapter(ITree.ROOT_ID, TreePointer(version.tree))
                    nodeList.add(rootNode)
                }

                model["rootNodes"] = nodeList
                call.respond(ThymeleafContent("content_explorer", model))
            }
            get("/content/style.css") {
                call.respondFile(File("src/main/resources/templates/style.css"))
            }
        }
    }
}
