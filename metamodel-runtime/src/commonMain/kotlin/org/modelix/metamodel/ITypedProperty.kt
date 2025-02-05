/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modelix.metamodel

import org.modelix.model.api.INode
import org.modelix.model.api.IProperty
import org.modelix.model.api.getPropertyValue
import org.modelix.model.api.setPropertyValue

interface ITypedProperty<ValueT : Any?> : ITypedConceptFeature {
    fun untyped(): IProperty
    fun serializeValue(value: ValueT): String?
    fun deserializeValue(serialized: String?): ValueT
}
fun <ValueT> INode.setTypedPropertyValue(property: ITypedProperty<ValueT>, value: ValueT) {
    setPropertyValue(property.untyped(), property.serializeValue(value))
}
fun <ValueT> INode.getTypedPropertyValue(property: ITypedProperty<ValueT>): ValueT {
    return property.deserializeValue(getPropertyValue(property.untyped()))
}