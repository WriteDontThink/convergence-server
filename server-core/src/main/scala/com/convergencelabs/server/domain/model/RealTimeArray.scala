package com.convergencelabs.server.domain.model

import com.convergencelabs.server.domain.model.ot.Operation
import com.convergencelabs.server.domain.model.ot.ObjectAddPropertyOperation
import com.convergencelabs.server.domain.model.ot.ObjectSetOperation
import com.convergencelabs.server.domain.model.ot.ObjectSetPropertyOperation
import com.convergencelabs.server.domain.model.ot.ObjectRemovePropertyOperation
import com.convergencelabs.server.domain.model.ot.ObjectRemovePropertyOperation
import com.convergencelabs.server.domain.model.ot.ObjectSetPropertyOperation
import com.convergencelabs.server.domain.model.ot.ArrayInsertOperation
import com.convergencelabs.server.domain.model.ot.DiscreteOperation
import com.convergencelabs.server.domain.model.ot.ArrayRemoveOperation
import com.convergencelabs.server.domain.model.ot.ArrayReplaceOperation
import com.convergencelabs.server.domain.model.ot.ArrayMoveOperation
import com.convergencelabs.server.domain.model.ot.ArraySetOperation
import com.convergencelabs.server.domain.model.ot.ArrayMoveOperation
import com.convergencelabs.server.domain.model.ot.ArrayReplaceOperation
import com.convergencelabs.server.domain.model.ot.ArrayRemoveOperation
import com.convergencelabs.server.domain.model.ot.ArrayInsertOperation
import com.convergencelabs.server.domain.model.ot.ArraySetOperation
import org.json4s.JsonAST.JArray
import scala.util.Try

class RealTimeArray(
  private[this] val model: RealTimeModel,
  private[this] val parent: Option[RealTimeContainerValue],
  private[this] val parentField: Option[Any],
  private[this] val value: JArray)
    extends RealTimeContainerValue(model, parent, parentField) {

  var i = 0;
  var children = value.arr.map {
    x => this.model.createValue(Some(this), Some({ i += 1; i }), x)
  }

  def value(): List[_] = {
    children.map({ v => v.value() })
  }

  def processOperation(op: DiscreteOperation): Try[Unit] = Try {
    op match {
      case insert: ArrayInsertOperation => processInsertOperation(insert)
      case remove: ArrayRemoveOperation => processRemoveOperation(remove)
      case replace: ArrayReplaceOperation => processReplaceOperation(replace)
      case reorder: ArrayMoveOperation => processReorderOperation(reorder)
      case value: ArraySetOperation => processSetValueOperation(value)
      case _ => throw new Error("Invalid operation for RealTimeObject")
    }
  }

  def processInsertOperation(op: ArrayInsertOperation): Unit = {
    val child = this.model.createValue(Some(this), Some(parentField), op.value)
    this.children = this.children.patch(op.index, List(child), 0)
    this.updateIndices(op.index + 1, this.children.length)
  }

  def processRemoveOperation(op: ArrayRemoveOperation): Unit = {
    val oldChild = this.children(op.index)
    this.children = this.children.patch(op.index, List(), 1)
    this.updateIndices(op.index, this.children.length)
  }

  def processReplaceOperation(op: ArrayReplaceOperation): Unit = {
    val oldChild = this.children(op.index)
    val child = this.model.createValue(Some(this), Some(parentField), op.value)
    this.children = this.children.patch(op.index, List(child), 1)
  }

  def processReorderOperation(op: ArrayMoveOperation): Unit = {
    val child = this.children(op.fromIndex)
    this.children = this.children.patch(op.fromIndex, List(), 1)
    this.children = this.children.patch(op.toIndex, List(child), 0)
    this.updateIndices(op.fromIndex, op.toIndex)
  }

  def processSetValueOperation(op: ArraySetOperation): Unit = {
    var i = 0;
    this.children = op.value.arr.map {
      x => this.model.createValue(Some(this), Some({ i += 1; i }), x)
    }
  }

  private[this] def updateIndices(fromIndex: Int, toIndex: Int): Unit = {
    for (i <- fromIndex to toIndex) {
      this.children(i).parentField = i
    }
  }
}