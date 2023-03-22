package org.modelix.model.server.handlers

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.thymeleaf.*
import org.apache.cxf.transport.commons_text.StringEscapeUtils
import org.modelix.authorization.KeycloakScope
import org.modelix.authorization.asResource
import org.modelix.authorization.getUserName
import org.modelix.authorization.requiresPermission
import org.modelix.model.LinearHistory
import org.modelix.model.api.*
import org.modelix.model.client.IModelClient
import org.modelix.model.lazy.CLTree
import org.modelix.model.lazy.CLVersion
import org.modelix.model.lazy.CLVersion.Companion.createRegularVersion
import org.modelix.model.lazy.KVEntryReference
import org.modelix.model.lazy.RepositoryId
import org.modelix.model.metameta.MetaModelBranch
import org.modelix.model.operations.OTBranch
import org.modelix.model.operations.RevertToOp
import org.modelix.model.operations.applyOperation
import org.modelix.model.persistent.CPVersion.Companion.DESERIALIZER
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.collections.HashSet
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.emptySet
import kotlin.collections.joinToString
import kotlin.collections.map
import kotlin.collections.mapOf
import kotlin.collections.mutableMapOf
import kotlin.collections.set
import kotlin.collections.toTypedArray

class HistoryHandler(private val client: IModelClient) {

    fun init(application: Application) {
        application.routing {
            get("/history/") {
                val model = mutableMapOf<String, Any>()
                model["repoUrlsAndNames"] = knownRepositoryIds.map { "${escapeURL(it.repository)}/${escapeURL(it.branch)}/" to it }
                call.respond(ThymeleafContent("history", model))
            }
            get("/history/{repoId}/{branch}/") {
                val repositoryId = call.parameters["repoId"]!!
                val branch = call.parameters["branch"]!!
                val params = call.request.queryParameters
                val limit = toInt(params["limit"], 500)
                val skip = toInt(params["skip"], 0)
                val stringWriter = StringWriter()
                buildRepositoryHistory(PrintWriter(stringWriter), RepositoryAndBranch(repositoryId, branch), params["head"], skip, limit)
                val model = mapOf("repoHistory" to stringWriter.toString())
                call.respond(ThymeleafContent("history_repository", model))
            }
            requiresPermission("history".asResource(), KeycloakScope.WRITE) {
                post("/history/{repoId}/{branch}/revert") {
                    val repositoryId = call.parameters["repoId"]!!
                    val branch = call.parameters["branch"]!!
                    val params = call.receiveParameters()
                    val fromVersion = params["from"]!!
                    val toVersion = params["to"]!!
                    val user = getUserName()
                    revert(RepositoryAndBranch(repositoryId, branch), fromVersion, toVersion, user)
                    call.respondRedirect(".")
                }
                post("/history/{repoId}/{branch}/delete") {
                    val repositoryId = call.parameters["repoId"]!!
                    val branch = call.parameters["branch"]!!
                    client.put(RepositoryId(repositoryId).getBranchKey(branch), null)
                    call.respondRedirect(".")
                }
            }
        }
    }

    fun revert(repositoryAndBranch: RepositoryAndBranch, from: String?, to: String?, author: String?) {
        val versionHash = client[repositoryAndBranch.branchKey]
        val version = CLVersion(versionHash!!, client.storeCache!!)
        val branch = OTBranch(PBranch(version.tree, client.idGenerator), client.idGenerator, client.storeCache!!)
        branch.runWriteT { t ->
            t.applyOperation(RevertToOp(KVEntryReference(from!!, DESERIALIZER), KVEntryReference(to!!, DESERIALIZER)))
        }
        val (ops, tree) = branch.operationsAndTree
        val newVersion = createRegularVersion(
            client.idGenerator.generate(),
            LocalDateTime.now().toString(),
            author ?: "<server>",
            (tree as CLTree),
            version,
            ops.map { it.getOriginalOp() }.toTypedArray()
        )
        client.put(repositoryAndBranch.branchKey, newVersion.write())
    }

    @Deprecated("Use RepositoriesManager")
    val knownRepositoryIds: Set<RepositoryAndBranch>
        get() {
            val result: MutableSet<RepositoryAndBranch> = HashSet()
            val infoVersionHash = client[RepositoryId("info").getBranchKey()] ?: return emptySet()
            val infoVersion = CLVersion(infoVersionHash, client.storeCache!!)
            val infoBranch: IBranch = MetaModelBranch(PBranch(infoVersion.tree, IdGeneratorDummy()))
            infoBranch.runReadT { t: IReadTransaction ->
                for (infoNodeId in t.getChildren(ITree.ROOT_ID, "info")) {
                    for (repositoryNodeId in t.getChildren(infoNodeId, "repositories")) {
                        val repositoryId = t.getProperty(repositoryNodeId, "id") ?: continue
                        result.add(RepositoryAndBranch(repositoryId))
                        for (branchNodeId in t.getChildren(repositoryNodeId, "branches")) {
                            val branchName = t.getProperty(branchNodeId, "name") ?: continue
                            result.add(RepositoryAndBranch(repositoryId, branchName))
                        }
                    }
                }
            }
            return result
        }

    private fun buildRepositoryHistory(out: PrintWriter, repositoryAndBranch: RepositoryAndBranch, headHash: String?, skip: Int, limit: Int) {
        val latestVersion = CLVersion(client[repositoryAndBranch.branchKey]!!, client.storeCache!!)
        val headVersion = if (headHash == null || headHash.length == 0) latestVersion else CLVersion(headHash, client.storeCache!!)
        var version: CLVersion? = headVersion
        var rowIndex = 0
        out.append("<h1>History for Repository ")
        out.append(escape(repositoryAndBranch.toString()))
        out.append("</h1>")

        out.append("""
            <div>
            <form action='delete' method='POST'>
            <input type='submit' value='Delete'/>
            </form>
            </div>
        """)

        val buttons = Runnable {
            out.append("<div class='BtnGroup'>")
            if (skip == 0) {
                out.append("<a class='BtnGroup-item' href='?head=" + escapeURL(latestVersion.hash) + "&skip=0&limit=" + limit + "'>Newer</a>")
            } else {
                out.append("<a class='BtnGroup-item' href='?head=" + escapeURL(headVersion.hash) + "&skip=" + Math.max(0, skip - limit) + "&limit=" + limit + "'>Newer</a>")
            }
            out.append("<a class='BtnGroup-item' href='?head=" + escapeURL(headVersion.hash) + "&skip=" + (skip + limit) + "&limit=" + limit + "'>Older</a>")
            out.append("</div>")
        }
        buttons.run()
        out.append("<table>")
        out.append("<thead><tr><th>ID<br/>Hash</th><th>Author</th><th>Time</th><th>Operations</th><th></th></tr></thead><tbody>")
        try {
            while (version != null) {
                if (rowIndex >= skip) {
                    createTableRow(out, version, latestVersion)
                    if (version.isMerge()) {
                        for (v in LinearHistory(version.baseVersion!!.hash).load(version.getMergedVersion1()!!, version.getMergedVersion2()!!)) {
                            createTableRow(out, v, latestVersion)
                            rowIndex++
                            if (rowIndex >= skip + limit) {
                                break
                            }
                        }
                    }
                }
                rowIndex++
                if (rowIndex >= skip + limit) {
                    break
                }
                version = version.baseVersion
            }
        } catch (ex: Exception) {
            out.append("""<tr><td colspan="5"><pre>${escape(ex.toString())}\n${ex.stackTraceToString()}</pre></td></tr>""")
        }
        out.append("</tbody></table>")
        buttons.run()

    }

    fun createTableRow(out: PrintWriter, version: CLVersion, latestVersion: CLVersion) {
        out.append("""
            <tr>
        <td>
            ${java.lang.Long.toHexString(version.id)}
            <br/>
            <span class='hash'>${version.hash}"</span>
        </td>
        <td>${nbsp(escape(version.author))}</td>
        <td style='white-space: nowrap;'>${escape(reformatTime(version.time))}</td>
        <td>""")
        val opsDescription = if (version.isMerge()) {
            "merge " + version.getMergedVersion1()!!.id + " + " + version.getMergedVersion2()!!.id + " (base " + version.baseVersion + ")"
        } else {
            if (version.operationsInlined()) {
                "<ul><li>" + version.operations.joinToString("</li><li>") { it.toString() } + "</li></ul>"
            } else {
                "(" + version.numberOfOperations + ") "
            }
        }
        out.append(opsDescription)
        out.append("""</td>
            <td>
            <form action='revert' method='POST'>
            <input type='hidden' name='from' value='${escapeURL(latestVersion.hash)}'/>
            <input type='hidden' name='to' value='${escapeURL(version.hash)}'/>
            <input type='submit' value='Revert To'/>
            </form>
            </td>
            </tr>
        """)
    }

    private fun reformatTime(dateTimeStr: String?): String {
        if (dateTimeStr == null) return ""
        val dateTime = LocalDateTime.parse(dateTimeStr)
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }

    private fun escape(text: String?): String? {
        return StringEscapeUtils.escapeHtml4(text)
    }

    private fun escapeURL(text: String?): String? {
        return URLEncoder.encode(text, StandardCharsets.UTF_8)
    }

    private fun nbsp(text: String?): String? {
        return text?.replace(" ", "&nbsp;")
    }

    private fun toInt(text: String?, defaultValue: Int): Int {
        try {
            if (!text.isNullOrEmpty()) {
                return text.toInt()
            }
        } catch (ex: NumberFormatException) {
        }
        return defaultValue
    }

    @Deprecated("Use BranchReference")
    data class RepositoryAndBranch(val repository: String, val branch: String = RepositoryId.DEFAULT_BRANCH) {
        val branchKey: String
            get() = RepositoryId(repository).getBranchKey(branch)

        override fun toString() = "$repository/$branch"
    }
}