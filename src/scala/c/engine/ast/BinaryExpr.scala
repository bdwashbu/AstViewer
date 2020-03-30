package scala.c.engine
package ast

import org.eclipse.cdt.core.dom.ast.IASTBinaryExpression._
import org.eclipse.cdt.core.dom.ast._
import org.eclipse.cdt.internal.core.dom.parser.c.{CArrayType, CBasicType, CPointerType, CStructure}
import IBasicType.Kind._

import scala.annotation.switch


object BinaryExpr {

  def calculateBoolean(left: AnyVal, right: AnyVal, operator: Int): Boolean = (operator: @switch) match {
    case `op_greaterThan` =>
      (left, right) match {
        case (x: Int, y: Int) => x > y
        case (x: Int, y: Float) => x > y
        case (x: Int, y: Double) => x > y
        case (x: Int, y: Long) => x > y

        case (x: Float, y: Int) => x > y
        case (x: Float, y: Double) => x > y
        case (x: Float, y: Float) => x > y
        case (x: Float, y: Long) => x > y

        case (x: Double, y: Int) => x > y
        case (x: Double, y: Double) => x > y
        case (x: Double, y: Float) => x > y
        case (x: Double, y: Long) => x > y

        case (x: Long, y: Int) => x > y
        case (x: Long, y: Float) => x > y
        case (x: Long, y: Double) => x > y
        case (x: Long, y: Long) => x > y
        case _ => false
      }
    case `op_logicalAnd` =>
      TypeHelper.resolveBoolean(left) && TypeHelper.resolveBoolean(right)
    case `op_logicalOr` =>
      TypeHelper.resolveBoolean(left) || TypeHelper.resolveBoolean(right)
    case `op_equals` =>
      left == right
    case `op_notequals` =>
      !calculateBoolean(left, right, op_equals)
    case `op_greaterEqual` =>
      calculateBoolean(left, right, op_greaterThan) || calculateBoolean(left, right, op_equals)
    case `op_lessThan` =>
      !calculateBoolean(left, right, op_greaterEqual)
    case `op_lessEqual` =>
      !calculateBoolean(left, right, op_greaterThan)
  }

  def evaluatePointerArithmetic(ptr: ValueType, offset: Int, operator: Int)(implicit state: State): RValue = {
    val rValue = TypeHelper.resolve(ptr)

    // For some reason double pointers should only use sizeof().  Not sure why.
    val theType = if (ptr.isInstanceOf[LValue] && !TypeHelper.getPointerType(ptr.theType).isInstanceOf[CPointerType]) {
      TypeHelper.getPointerType(ptr.theType)
    } else {
      ptr.theType
    }

    val value = TypeHelper.sizeof(theType) * offset
    val bias = if (operator == `op_minus`) -1 else 1

    val computedOffset = value * bias

    Address(rValue.value.asInstanceOf[Int] + computedOffset, rValue.theType)
  }

  def calculate(left: AnyVal, right: AnyVal, operator: Int)(implicit state: State): AnyVal = {
    // Because of integer promotion, C never does math on anything less than int's

    val op1 = left match {
      case theChar: char => theChar.toInt
      case theShort: short => theShort.toInt
      case x => x
    }

    val op2 = right match {
      case theChar: char => theChar.toInt
      case theShort: short => theShort.toInt
      case x => x
    }

    import annotation.switch

    op1 match {
      case x: Int => (operator: @switch) match {
        case `op_assign` =>
          op2
        case `op_multiply` | `op_multiplyAssign` =>
          op2 match {
            case y: Int => x * y
            case y: Float => x * y
            case y: Double => x * y
            case y: Long => x * y
          }
        case `op_plus` | `op_plusAssign` =>
          op2 match {
            case y: Int => x + y
            case y: Float => x + y
            case y: Double => x + y
            case y: Long => x + y
          }
        case `op_minus` | `op_minusAssign` =>
          op2 match {
            case y: Int => x - y
            case y: Float => x - y
            case y: Double => x - y
            case y: Long => x - y
          }
        case `op_divide` | `op_divideAssign` =>
          op2 match {
            case y: Int => x / y
            case y: Float => x / y
            case y: Double => x / y
            case y: Long => x / y
          }
        case `op_shiftRight` | `op_shiftRightAssign` =>
          x >> op2.asInstanceOf[Int]
        case `op_shiftLeft` | `op_shiftLeftAssign` =>
          x << op2.asInstanceOf[Int]
        case `op_modulo` =>
          op2 match {
            case y: Long => x % y
            case y: Int => x % y
          }
        case `op_binaryOr`  | `op_binaryOrAssign`=>
          op2 match {
            case y: Int => x | y
            case y: Long => x | y
          }
        case `op_binaryXor` | `op_binaryXorAssign` =>
          op2 match {
            case y: Int => x ^ y
            case y: Long => x ^ y
          }
        case `op_binaryAnd` | `op_binaryAndAssign` =>
          op2 match {
            case y: Int => x & y
            case y: Long => x & y
          }
        case _ =>
          calculateBoolean(op1, op2, operator)
      }
      case x: Long => (operator: @switch) match {
        case `op_assign` =>
          op2
        case `op_multiply` | `op_multiplyAssign` =>
          op2 match {
            case y: Int => x * y
            case y: Float => x * y
            case y: Double => x * y
            case y: Long => x * y
          }
        case `op_plus` | `op_plusAssign` =>
          op2 match {
            case y: Int => x + y
            case y: Float => x + y
            case y: Double => x + y
            case y: Long => x + y
          }
        case `op_minus` | `op_minusAssign` =>
          op2 match {
            case y: Int => x - y
            case y: Float => x - y
            case y: Double => x - y
            case y: Long => x - y
          }
        case `op_divide` | `op_divideAssign` =>
          op2 match {
            case y: Int => x / y
            case y: Float => x / y
            case y: Double => x / y
            case y: Long => x / y
          }
        case `op_shiftRight` | `op_shiftRightAssign` =>
          x >> op2.asInstanceOf[Int]
        case `op_shiftLeft` | `op_shiftLeftAssign` =>
          x << op2.asInstanceOf[Int]
        case `op_modulo` =>
          op2 match {
            case y: Long => x % y
            case y: Int => x % y
          }
        case `op_binaryOr`  | `op_binaryOrAssign`=>
          op2 match {
            case y: Int => x | y
            case y: Long => x | y
          }
        case `op_binaryXor` | `op_binaryXorAssign` =>
          op2 match {
            case y: Int => x ^ y
            case y: Long => x ^ y
          }
        case `op_binaryAnd` | `op_binaryAndAssign` =>
          op2 match {
            case y: Int => x & y
            case y: Long => x & y
          }
        case _ =>
          calculateBoolean(op1, op2, operator)
      }
      case x: Double => (operator: @switch) match {
        case `op_assign` =>
          op2
        case `op_multiply` | `op_multiplyAssign` =>
          op2 match {
            case y: Int => x * y
            case y: Float => x * y
            case y: Double => x * y
            case y: Long => x * y
          }
        case `op_plus` | `op_plusAssign` =>
          op2 match {
            case y: Int => x + y
            case y: Float => x + y
            case y: Double => x + y
            case y: Long => x + y
          }
        case `op_minus` | `op_minusAssign` =>
          op2 match {
            case y: Int => x - y
            case y: Float => x - y
            case y: Double => x - y
            case y: Long => x - y
          }
        case `op_divide` | `op_divideAssign` =>
          op2 match {
            case y: Int => x / y
            case y: Float => x / y
            case y: Double => x / y
            case y: Long => x / y
          }
        case _ =>
          calculateBoolean(op1, op2, operator)
      }
      case x: Float => (operator: @switch) match {
        case `op_assign` =>
          op2
        case `op_multiply` | `op_multiplyAssign` =>
          op2 match {
            case y: Int => x * y
            case y: Float => x * y
            case y: Double => x * y
            case y: Long => x * y
          }
        case `op_plus` | `op_plusAssign` =>
          op2 match {
            case y: Int => x + y
            case y: Float => x + y
            case y: Double => x + y
            case y: Long => x + y
          }
        case `op_minus` | `op_minusAssign` =>
          op2 match {
            case y: Int => x - y
            case y: Float => x - y
            case y: Double => x - y
            case y: Long => x - y
          }
        case `op_divide` | `op_divideAssign` =>
          op2 match {
            case y: Int => x / y
            case y: Float => x / y
            case y: Double => x / y
            case y: Long => x / y
          }
        case _ =>
          calculateBoolean(op1, op2, operator)
      }
      case _ =>
        calculateBoolean(op1, op2, operator)
    }
  }

  def evaluate(x: ValueType, y: ValueType, operator: Int)(implicit state: State): RValue = {
    val left = TypeHelper.resolve(x)
    val right = TypeHelper.resolve(y)

    val isLeftPointer = TypeHelper.isPointerOrArray(x)
    val isRightPointer = TypeHelper.isPointerOrArray(y)

    if (isLeftPointer && (operator == `op_minus` || operator == `op_plus`)) {
      val rightValue = TypeHelper.cast(TypeHelper.intType, right.value).value.asInstanceOf[Int]
      if (isRightPointer) {
        val leftSize = TypeHelper.sizeof(right.theType)
        val result = (left.value.asInstanceOf[Int] - rightValue) / leftSize
        Address(result, left.theType)
      } else {
        evaluatePointerArithmetic(left, rightValue, operator)
      }
    } else if (isRightPointer && operator == `op_plus`) {
      val leftValue = TypeHelper.cast(TypeHelper.intType, left.value).value.asInstanceOf[Int]
      val rightPtrSize = TypeHelper.sizeof(right.theType)
      val result = leftValue * rightPtrSize + right.value.asInstanceOf[Int]
      Address(result, right.theType)
    } else {
      if (right.isInstanceOf[FileRValue]) {
        right
      } else {
        val result = calculate(left.value,  right.value, operator)
        RValue(result, left.theType)
      }
    }
  }
}