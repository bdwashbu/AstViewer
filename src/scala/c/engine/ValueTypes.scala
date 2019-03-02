package scala.c.engine

import org.eclipse.cdt.core.dom.ast._
import org.eclipse.cdt.internal.core.dom.parser.c._

abstract class ValueType {
  def theType: IType
  def rawType: IType
}

trait LValue extends ValueType {
  val address: Int
  val theType: IType
  val bitOffset: Int
  val state: State
  val sizeInBits: Int

  def sizeof: Int
  def rValue: RValue = {
    if (TypeHelper.isPointer(this)) {
      Address(getValue.value.asInstanceOf[Int], TypeHelper.getPointerType(theType))
    } else {
      RValue(getValue.value, theType)
    }
  }

  def getValue = if (theType.isInstanceOf[IArrayType]) {
    RValue(address, theType)
  } else {
    state.Stack.readFromMemory(address, theType, bitOffset, sizeInBits)
  }

  //protected def getValue = state.Stack.readFromMemory(address, theType, bitOffset, sizeInBits)
  def setValue(newVal: AnyVal) = {
    state.Stack.writeToMemory(newVal, address, theType, bitOffset, sizeInBits)
  }

  def toByteArray = state.readDataBlock(address, sizeof)(state)
}

object LValue {
  def unapply(info: LValue): Option[(Int, IType)] = Some((info.address, info.theType))
  def apply(theState: State, addr: Int, aType: IType) =
    new LValue {
      val address = addr
      val state = theState
      val bitOffset = 0
      val sizeInBits = sizeof * 8
      val theType = TypeHelper.stripSyntheticTypeInfo(aType)
      val rawType = aType
      //def sizeof = TypeHelper.sizeof(theType)(state)}
      val sizeof = {
        TypeHelper.getPointerSize(theType)(state)
      }
    }
}

case class StringLiteral(value: String) extends ValueType {
  val theType = new CPointerType(new CBasicType(IBasicType.Kind.eChar, 0), 0)
  val rawType = theType
}

case class TypeInfo(value: IType) extends ValueType {
  val theType = value
  val rawType = theType
}

object RValue {
  def unapply(rvalue: RValue): Option[(AnyVal, IType)] = Some((rvalue.value, rvalue.theType))
  def apply(theValue: AnyVal, aType: IType) =
    new RValue {val theType = TypeHelper.stripSyntheticTypeInfo(aType); val rawType = aType; val value = theValue;}
}

abstract class RValue extends ValueType {
  val value: AnyVal
  val theType: IType

  override def toString = {
    "RValue(" + value + ", " + theType + ")"
  }
}

case class Address(value: Int, theType: IType) extends RValue {
  override def toString = {
    "Address(" + value + ", " + theType + ")"
  }
  val rawType = theType
}

case class Field(state: State, address: Int, bitOffset: Int, theType: IType, sizeInBits: Int) extends LValue {
  val sizeof = sizeInBits / 8
  val rawType = theType
}

case class Variable(name: String, state: State, aType: IType) extends LValue {

  val theType = TypeHelper.stripSyntheticTypeInfo(aType)
  val rawType = aType
  val bitOffset = 0
  val sizeInBits = sizeof * 8

  def setArray(values: List[RValue]): Unit = {
    state.writeDataBlock(values, address)(state)
  }

  def allocateSpace(state: State, aType: IType): Int = {
    state.allocateSpace(TypeHelper.sizeof(theType)(state))
  }

  val (allocate, theSize) = {
    val current = state.Stack.insertIndex
    val result = allocateSpace(state, theType)
    val size = state.Stack.insertIndex - current
    (result, size)
  }

  val address = allocate

  def sizeof = theSize

  // need this for function-scoped static vars
  var isInitialized = false

  override def toString = {
    "Variable(" + name + ", " + address + ", " + theType + ")"
  }
}
