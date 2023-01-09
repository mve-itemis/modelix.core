package org.modelix.editor

import org.modelix.metamodel.GeneratedConcept
import org.modelix.metamodel.GeneratedReferenceLink
import org.modelix.metamodel.ITypedConcept
import org.modelix.metamodel.ITypedNode
import org.modelix.metamodel.getPropertyValue
import org.modelix.metamodel.typed
import org.modelix.metamodel.typedUnsafe
import org.modelix.metamodel.untyped
import org.modelix.metamodel.untypedReference
import org.modelix.model.api.IChildLink
import org.modelix.model.api.INode
import org.modelix.model.api.IProperty
import org.modelix.model.api.addNewChild
import org.modelix.model.api.getChildren
import org.modelix.model.api.getContainmentLink
import org.modelix.model.api.getReferenceTarget
import org.modelix.model.api.index
import org.modelix.model.api.moveChild
import org.modelix.model.api.setPropertyValue
import org.modelix.model.api.setReferenceTarget

abstract class CellTemplate<NodeT : ITypedNode, ConceptT : ITypedConcept>(val concept: GeneratedConcept<NodeT, ConceptT>) {
    val properties = CellProperties()
    private val children: MutableList<CellTemplate<NodeT, ConceptT>> = ArrayList()
    private var reference: ICellTemplateReference? = null
    val withNode: MutableList<(node: NodeT)->Unit> = ArrayList()
    fun apply(context: CellCreationContext, node: NodeT): CellData {
        val cellData = createCell(context, node)
        cellData.properties.addAll(properties)
        cellData.children.addAll(applyChildren(context, node, cellData))
        if (properties[CommonCellProperties.layout] == ECellLayout.VERTICAL) {
            cellData.children.drop(1).forEach { (it as CellData).properties[CommonCellProperties.onNewLine] = true }
        }
        withNode.forEach { it(node) }
        val cellReference: TemplateCellReference = createCellReference(node)
        cellData.cellReferences.add(cellReference)
        applyTextReplacement(cellData, context.editorState)
        return cellData
    }
    protected open fun applyChildren(context: CellCreationContext, node: NodeT, cell: CellData): List<CellData> {
        return children.map { it.apply(context, node) }
    }
    protected abstract fun createCell(context: CellCreationContext, node: NodeT): CellData

    open fun getInstantiationActions(location: INonExistingNode, parameters: CodeCompletionParameters): List<ICodeCompletionAction>? {
        return children.asSequence().mapNotNull { it.getInstantiationActions(location, parameters) }.firstOrNull()
    }

    fun getSideTransformActions(before: Boolean, nodeToReplace: INode): List<ICodeCompletionAction>? {
        val symbols = getGrammarSymbols().toList()
        val conceptToReplace = nodeToReplace.concept ?: return null
        return symbols.mapIndexedNotNull { index, symbol ->
            if (symbol is ChildCellTemplate<*, *> && conceptToReplace.isSubConceptOf(symbol.link.targetConcept)) {
                val prevNextIndex = if (before)index - 1 else index + 1
                val prevNextSymbol = symbols.getOrNull(prevNextIndex) ?: return@mapIndexedNotNull null
                return@mapIndexedNotNull prevNextSymbol.createWrapperAction(nodeToReplace, symbol.link)
            }
            return@mapIndexedNotNull null
        }.flatten()
    }

    fun getGrammarSymbols(): Sequence<IGrammarSymbol> {
        return if (this is IGrammarSymbol) {
            sequenceOf(this)
        } else {
            children.asSequence().flatMap { it.getGrammarSymbols() }
        }
    }

    fun addChild(child: CellTemplate<NodeT, ConceptT>) {
        children.add(child)
        reference?.let { child.setReference(ChildCellTemplateReference(it, children.size - 1)) }
    }

    fun getChildren(): List<CellTemplate<NodeT, ConceptT>> = children

    fun setReference(ref: ICellTemplateReference) {
        if (reference != null) throw IllegalStateException("reference is already set")
        reference = ref
        children.forEachIndexed { index, child -> child.setReference(ChildCellTemplateReference(ref, index)) }
    }

    fun getReference() = reference ?: throw IllegalStateException("reference isn't set yet")

    fun createCellReference(node: ITypedNode) = TemplateCellReference(getReference(), node.untypedReference())

    private fun applyTextReplacement(cellData: CellData, editorState: EditorState) {
        if (cellData is TextCellData) {
            val cellRef = cellData.cellReferences.firstOrNull()
            if (cellRef != null) {
                editorState.textReplacements[cellRef]
                    ?.let { cellData.properties[CommonCellProperties.textReplacement] = it }
                if (cellData.properties[CellActionProperties.replaceText] == null) {
                    cellData.properties[CellActionProperties.replaceText] = OverrideText(cellData)
                }
            }
        }
        cellData.children.filterIsInstance<CellData>().forEach { applyTextReplacement(it, editorState) }
    }
}

interface IGrammarSymbol {
    fun createWrapperAction(nodeToWrap: INode, wrappingLink: IChildLink): List<ICodeCompletionAction> {
        return emptyList()
    }
}

class OverrideText(val cell: TextCellData) : ITextChangeAction {
    override fun isValid(value: String?): Boolean {
        return value == cell.text
    }

    override fun replaceText(editor: EditorComponent, range: IntRange, replacement: String, newText: String): Boolean {
        editor.state.textReplacements[cell.cellReferences.first()] = newText
        return true
    }
}

class ConstantCellTemplate<NodeT : ITypedNode, ConceptT : ITypedConcept>(concept: GeneratedConcept<NodeT, ConceptT>, val text: String)
    : CellTemplate<NodeT, ConceptT>(concept), IGrammarSymbol {
    override fun createCell(context: CellCreationContext, node: NodeT) = TextCellData(text, "")
    override fun getInstantiationActions(location: INonExistingNode, parameters: CodeCompletionParameters): List<ICodeCompletionAction> {
        return listOf(InstantiateNodeAction(location))
    }

    override fun createWrapperAction(nodeToWrap: INode, wrappingLink: IChildLink): List<ICodeCompletionAction> {
        return listOf(WrapperAction(nodeToWrap, wrappingLink))
    }

    inner class WrapperAction(val nodeToWrap: INode, val wrappingLink: IChildLink) : ICodeCompletionAction {
        override fun isApplicable(parameters: CodeCompletionParameters): Boolean = true
        override fun getMatchingText(parameters: CodeCompletionParameters): String = text
        override fun getDescription(parameters: CodeCompletionParameters): String = concept.getShortName()
        override fun execute() {
            val wrapper = nodeToWrap.parent!!.addNewChild(nodeToWrap.getContainmentLink()!!, nodeToWrap.index(), concept)
            wrapper.moveChild(wrappingLink, 0, nodeToWrap)
        }
    }

    inner class InstantiateNodeAction(val location: INonExistingNode) : ICodeCompletionAction {
        override fun isApplicable(parameters: CodeCompletionParameters): Boolean = true

        override fun getMatchingText(parameters: CodeCompletionParameters): String {
            return text
        }

        override fun getDescription(parameters: CodeCompletionParameters): String {
            return concept.getShortName()
        }

        override fun execute() {
            location.replaceNode(concept)
        }
    }
}

class NewLineCellTemplate<NodeT : ITypedNode, ConceptT : ITypedConcept>(concept: GeneratedConcept<NodeT, ConceptT>)
    : CellTemplate<NodeT, ConceptT>(concept) {
    override fun createCell(context: CellCreationContext, node: NodeT): CellData {
        return CellData().also { cell -> cell.properties[CommonCellProperties.onNewLine] = true }
    }
}
class NoSpaceCellTemplate<NodeT : ITypedNode, ConceptT : ITypedConcept>(concept: GeneratedConcept<NodeT, ConceptT>)
    : CellTemplate<NodeT, ConceptT>(concept) {
    override fun createCell(context: CellCreationContext, node: NodeT): CellData {
        return CellData().also { cell -> cell.properties[CommonCellProperties.noSpace] = true }
    }
}
class CollectionCellTemplate<NodeT : ITypedNode, ConceptT : ITypedConcept>(concept: GeneratedConcept<NodeT, ConceptT>)
    : CellTemplate<NodeT, ConceptT>(concept) {
    override fun createCell(context: CellCreationContext, node: NodeT) = CellData()
}
class OptionalCellTemplate<NodeT : ITypedNode, ConceptT : ITypedConcept>(concept: GeneratedConcept<NodeT, ConceptT>)
    : CellTemplate<NodeT, ConceptT>(concept) {
    override fun createCell(context: CellCreationContext, node: NodeT): CellData {
        return CellData()
    }

    override fun applyChildren(context: CellCreationContext, node: NodeT, cell: CellData): List<CellData> {
        // TODO support other cell types as condition for the optional
        val childLinkCell = getChildren().filterIsInstance<ChildCellTemplate<NodeT, *>>().firstOrNull()
        if (childLinkCell == null || childLinkCell.getChildNodes(node).isNotEmpty()) {
            return super.applyChildren(context, node, cell)
        } else {
            return emptyList()
        }
    }

    override fun getInstantiationActions(location: INonExistingNode, parameters: CodeCompletionParameters): List<ICodeCompletionAction>? {
        return null // skip optional. Don't search in children.
    }
}

open class PropertyCellTemplate<NodeT : ITypedNode, ConceptT : ITypedConcept>(concept: GeneratedConcept<NodeT, ConceptT>, val property: IProperty)
    : CellTemplate<NodeT, ConceptT>(concept), IGrammarSymbol {
    var placeholderText: String = "<no ${property.name}>"
    var validator: (String) -> Boolean = { true }
    override fun createCell(context: CellCreationContext, node: NodeT): CellData {
        val value = node.getPropertyValue(property)
        val data = TextCellData(value ?: "", if (value == null) placeholderText else "")
        data.properties[CellActionProperties.replaceText] = ChangePropertyAction(node, property)
        data.cellReferences += PropertyCellReference(property, node.untypedReference())
        return data
    }
    override fun getInstantiationActions(location: INonExistingNode, parameters: CodeCompletionParameters): List<ICodeCompletionAction> {
        return if (validator(parameters.pattern)) {
            listOf(WrapPropertyValue(location, parameters.pattern))
        } else {
            emptyList()
        }
    }

    inner class WrapPropertyValue(val location: INonExistingNode, val value: String) : ICodeCompletionAction {
        override fun isApplicable(parameters: CodeCompletionParameters): Boolean {
            return true
        }

        override fun getMatchingText(parameters: CodeCompletionParameters): String {
            return value
        }

        override fun getDescription(parameters: CodeCompletionParameters): String {
            return concept.getShortName()
        }

        override fun execute() {
            location.getOrCreateNode(concept).setPropertyValue(property, value)
        }
    }
}

class ChangePropertyAction(val node: ITypedNode, val property: IProperty) : ITextChangeAction {
    override fun isValid(value: String?): Boolean {
        return true
    }

    override fun replaceText(editor: EditorComponent, range: IntRange, replacement: String, newText: String): Boolean {
        node.unwrap().setPropertyValue(property, newText)
        return true
    }
}

class ReferenceCellTemplate<NodeT : ITypedNode, ConceptT : ITypedConcept, TargetNodeT : ITypedNode, TargetConceptT : ITypedConcept>(
    concept: GeneratedConcept<NodeT, ConceptT>,
    val link: GeneratedReferenceLink<TargetNodeT, TargetConceptT>,
    val presentation: TargetNodeT.() -> String?
) : CellTemplate<NodeT, ConceptT>(concept), IGrammarSymbol {
    override fun createCell(context: CellCreationContext, node: NodeT): CellData = TextCellData(getText(node), "<no ${link.name}>")
    private fun getText(node: NodeT): String = getTargetNode(node)?.let(presentation) ?: ""
    private fun getTargetNode(sourceNode: NodeT): TargetNodeT? {
        return sourceNode.unwrap().getReferenceTarget(link)?.typedUnsafe()
    }
    override fun getInstantiationActions(location: INonExistingNode, parameters: CodeCompletionParameters): List<ICodeCompletionAction> {
        val specializedLocation = location.ofSubConcept(concept)
        val targets = specializedLocation.getVisibleReferenceTargets(link)
        return targets.map { WrapReferenceTarget(location, it, presentation(it.typedUnsafe()) ?: "") }
    }

    inner class WrapReferenceTarget(val location: INonExistingNode, val target: INode, val presentation: String): ICodeCompletionAction {
        override fun isApplicable(parameters: CodeCompletionParameters): Boolean {
            return true
        }

        override fun getMatchingText(parameters: CodeCompletionParameters): String {
            return presentation
        }

        override fun getDescription(parameters: CodeCompletionParameters): String {
            return concept.getShortName()
        }

        override fun execute() {
            val sourceNode = location.getOrCreateNode(concept)
            sourceNode.setReferenceTarget(link, target)
        }
    }
}

class FlagCellTemplate<NodeT : ITypedNode, ConceptT : ITypedConcept>(
    concept: GeneratedConcept<NodeT, ConceptT>,
    property: IProperty,
    val text: String
) : PropertyCellTemplate<NodeT, ConceptT>(concept, property), IGrammarSymbol {
    override fun createCell(context: CellCreationContext, node: NodeT) = if (node.getPropertyValue(property) == "true") TextCellData(text, "") else CellData()
    override fun getInstantiationActions(location: INonExistingNode, parameters: CodeCompletionParameters): List<ICodeCompletionAction> {
        // TODO
        return listOf()
    }
}

class ChildCellTemplate<NodeT : ITypedNode, ConceptT : ITypedConcept>(
    concept: GeneratedConcept<NodeT, ConceptT>,
    val link: IChildLink
) : CellTemplate<NodeT, ConceptT>(concept), IGrammarSymbol {
    override fun createCell(context: CellCreationContext, node: NodeT) = CellData().also { cell ->
        val childNodes = getChildNodes(node)
        val substitutionPlaceholder = context.editorState.substitutionPlaceholderPositions[createCellReference(node)]
        val placeholderIndex = substitutionPlaceholder?.index?.coerceIn(0..childNodes.size) ?: 0
        val addSubstitutionPlaceholder: (Int) -> Unit = { index ->
            val placeholderText = if (childNodes.isEmpty()) "<no ${link.name}>" else "<choose ${link.name}>"
            val placeholder = TextCellData("", placeholderText)
            placeholder.properties[CellActionProperties.substitute] =
                ReplaceNodeActionProvider(NonExistingChild(node.untyped().toNonExisting(), link, index)).after {
                    context.editorState.substitutionPlaceholderPositions.remove(createCellReference(node))
                }
            placeholder.cellReferences.add(PlaceholderCellReference(createCellReference(node)))
            cell.addChild(placeholder)
        }
        val addInsertActionCell: (Int) -> Unit = { index ->
            if (link.isMultiple) {
                val actionCell = CellData()
                val action = InsertSubstitutionPlaceholderAction(context.editorState, createCellReference(node), index)
                actionCell.properties[CellActionProperties.insert] = action
                cell.addChild(actionCell)
            }
        }
        if (childNodes.isEmpty()) {
            addSubstitutionPlaceholder(0)
        } else {
            val childCells = childNodes.map { ChildDataReference(it.typed()) }
            childCells.forEachIndexed { index, child ->
                if (substitutionPlaceholder != null && placeholderIndex == index) {
                    addSubstitutionPlaceholder(placeholderIndex)
                } else {
                    addInsertActionCell(index)
                }

                //child.parent?.removeChild(child) // child may be cached and is still attached to the old parent
                val wrapper = CellData() // allow setting properties by the parent, because the cell is already frozen
                wrapper.addChild(child)
                cell.addChild(wrapper)
            }
            if (substitutionPlaceholder != null && placeholderIndex == childNodes.size) {
                addSubstitutionPlaceholder(placeholderIndex)
            } else {
                addInsertActionCell(childNodes.size)
            }
        }
    }

    fun getChildNodes(node: NodeT) = node.unwrap().getChildren(link).toList()

    override fun getInstantiationActions(location: INonExistingNode, parameters: CodeCompletionParameters): List<ICodeCompletionAction> {
        // TODO
        return listOf()
    }
}
data class PlaceholderCellReference(val childCellRef: TemplateCellReference) : CellReference()

class InsertSubstitutionPlaceholderAction(
    val editorState: EditorState,
    val ref: TemplateCellReference,
    val index: Int
) : ICellAction {
    override fun isApplicable(): Boolean = true

    override fun execute(editor: EditorComponent) {
        editorState.substitutionPlaceholderPositions[ref] = SubstitutionPlaceholderPosition(index)
        editor.selectAfterUpdate {
            editor.resolveCell(PlaceholderCellReference(ref))
                .firstOrNull()?.layoutable()?.let { CaretSelection(it, 0) }
        }
    }
}

fun CellTemplate<*, *>.firstLeaf(): CellTemplate<*, *> = if (getChildren().isEmpty()) this else getChildren().first().firstLeaf()
