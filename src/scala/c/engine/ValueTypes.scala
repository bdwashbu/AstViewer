package scala.c.engine

import org.eclipse.cdt.core.dom.ast._
import org.eclipse.cdt.internal.core.dom.parser.c._

abstract class ValueType {
  def theType: IType
}

abstract class LValue(state: State) extends ValueType {
  val address: Int
  val theType: IType
  def sizeof: Int
  def value: RValue

  override def toString = {
    "AddressInfo(" + address + ", " + theType + ")"
  }
}

case class StringLiteral(value: String) extends ValueType {
  val theType = new CPointerType(new CBasicType(IBasicType.Kind.eChar, 0), 0)
}

case class TypeInfo(value: IType) extends ValueType {
  val theType = value
}

case class RValue(value: AnyVal, theType: IType) extends ValueType {
  override def toString = {
    "RValue(" + value + ", " + theType + ")"
  }
}

case class Field(state: State, address: Int, theType: IType, size: Int) extends LValue(state) {
  val sizeof = size
  def value = state.Stack.readFromMemory(address, theType)
}

case class MemoryLocation(state: State, address: Int, theType: IType) extends LValue(state) {
  val sizeof = TypeHelper.sizeof(theType)
  def value = state.Stack.readFromMemory(address, theType)
}

object LValue {
  def unapply(info: LValue): Option[(Int, IType)] = Some((info.address, info.theType))
}

case class Variable(name: String, state: State, theType: IType, dim: Seq[Int]) extends LValue(state) {

  def setArray(array: Array[RValue]): Unit = {
    state.setArray(array, address, TypeHelper.sizeof(theType))
  }

  def allocateSpace(state: State, aType: IType, dimensions: Seq[Int] = Seq()): Int = {
    def recurse(aType: IType, dimensions: Seq[Int]): Int = aType match {
      case array: IArrayType =>

        val size = if (array.hasSize) {
          array.getSize.numericalValue().toInt
        } else {
          1
        }

        if (array.getType.isInstanceOf[IArrayType]) { // multi-dimensional array
          val addr = state.allocateSpace(TypeHelper.sizeof(array) * size)
          for (i <- (0 until size)) {
            val subaddr = recurse(array.getType, dimensions.tail)
            state.Stack.writeToMemory(subaddr, addr + i * 4, TypeHelper.pointerType) // write pointer
          }
          addr
        } else {
          val arraySize = if (dimensions.size > 0) dimensions.head else size
          state.allocateSpace(TypeHelper.sizeof(array.getType) * arraySize)
        }
      case x =>
        state.allocateSpace(TypeHelper.sizeof(x))
    }

    recurse(aType, dimensions)
  }

  val (allocate, theSize) = {
    val current = state.Stack.insertIndex
    val result = allocateSpace(state, theType, dim.reverse)
    val size = state.Stack.insertIndex - current
    (result, size)
  }

  val address = allocate

  def sizeof = theSize

  // need this for function-scoped static vars
  var isInitialized = false

  def value = if (theType.isInstanceOf[IArrayType]) {
    RValue(address, theType)
  } else {
    state.Stack.readFromMemory(address, theType)
  }

  override def toString = {
    "Variable(" + name + ", " + address + ", " + theType + ")"
  }

  def setValues(values: List[ValueType]) = {
    var offset = 0
    values.foreach {
      case RValue(value, theType) =>
        state.Stack.writeToMemory(value, address + offset, theType)
        offset += TypeHelper.sizeof(theType)
    }
  }
}
