package org.modelix.editor

import org.modelix.metamodel.ITypedNode
import org.modelix.metamodel.untyped
import org.modelix.model.api.INode
import org.modelix.model.api.INodeReference
import org.modelix.model.api.IProperty

abstract class CellReference {

}

data class PropertyCellReference(val property: IProperty, val nodeRef: INodeReference) : CellReference()

fun EditorComponent.resolvePropertyCell(property: IProperty, nodeRef: INodeReference): Cell? =
    resolveCell(PropertyCellReference(property, nodeRef)).firstOrNull()

fun EditorComponent.resolvePropertyCell(property: IProperty, node: INode): Cell? =
    resolvePropertyCell(property, node.reference)

fun EditorComponent.resolvePropertyCell(property: IProperty, node: ITypedNode): Cell? =
    resolvePropertyCell(property, node.untyped())