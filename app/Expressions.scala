package app.astViewer

import org.eclipse.cdt.core.dom.ast._

import scala.collection.mutable.{ ListBuffer, Stack }
import scala.util.control.Exception.allCatch
import java.util.Formatter;
import java.util.Locale;
import java.math.BigInteger

object Expressions {

  def parse(expr: IASTExpression, direction: Direction, context: State, stack: State#VarStack): Seq[IASTNode] = expr match {
    case fieldRef: IASTFieldReference =>
      if (direction == Entering) {
        Seq(fieldRef.getFieldOwner)
      } else {
        Seq()
      }
    case subscript: IASTArraySubscriptExpression =>
      if (direction == Entering) {
        Seq(subscript.getArrayExpression, subscript.getArgument)
      } else {

        // index is first on the stack
        val index = context.stack.pop match {
          case VarRef(indexVarName) =>
            context.vars.resolveId(indexVarName).value.asInstanceOf[Int]
          case lit @ Literal(_) => lit.cast.asInstanceOf[Int]
          case x: Int =>
            x
        }

        val name = context.stack.pop
        val arrayVarPtr = context.vars.resolveId(name.asInstanceOf[VarRef].name)
        val arrayAddress = arrayVarPtr.value.asInstanceOf[Int]
        val arrayType = TypeResolver.resolve(stack.getType(Address(arrayAddress)))
        if (context.parsingAssignmentDest) {
          context.stack.push(Address(arrayAddress + index * TypeHelper.sizeof(arrayType)))
        } else {
          context.stack.push(stack.readVal(arrayAddress + index * TypeHelper.sizeof(arrayType)))
        }

        Seq()
      }
    case unary: IASTUnaryExpression =>
      import org.eclipse.cdt.core.dom.ast.IASTUnaryExpression._

      def resolveVar(variable: Any, func: (Int) => Unit) = {
        variable match {
          case VarRef(name) =>
            val variable = context.vars.resolveId(name)

            if (variable.isPointer) {
              func(variable.value.asInstanceOf[Int])
            } else {
              func(variable.address.address)
            }
          case addy @ Address(_) => func(addy.address)
          case int: Int          => int
        }
      }

      if (direction == Entering) {
        Seq(unary.getOperand)
      } else {
        unary.getOperator match {
          case `op_minus` =>

            def negativeResolver(variable: Variable) = resolveVar(variable.address, (address) => {
              context.stack.push(variable.typeName match {
                case "int"    => -stack.readVal(address).asInstanceOf[Int]
                case "double" => -stack.readVal(address).asInstanceOf[Double]
              })
            })

            val cast = context.stack.pop match {
              case lit @ Literal(_) => lit.cast
              case x            => x
            }

            cast match {
              case int: Int     => context.stack.push(-int)
              case doub: Double => context.stack.push(-doub)
              //case variable @ Variable(_) => negativeResolver(variable)
              case VarRef(name) =>
                val variable = context.vars.resolveId(name)
                negativeResolver(variable)
            }
          case `op_postFixIncr` =>
            resolveVar(context.stack.pop, (address) => {
              val currentVal = stack.readVal(address)
              context.stack.push(currentVal)
              stack.setValue(currentVal.asInstanceOf[Int] + 1, Address(address))
            })
          case `op_postFixDecr` =>
            resolveVar(context.stack.pop, (address) => {
              val currentVal = stack.readVal(address)
              context.stack.push(currentVal)
              stack.setValue(currentVal.asInstanceOf[Int] - 1, Address(address))
            })
          case `op_prefixIncr` =>
            resolveVar(context.stack.pop, (address) => {
              val currentVal = stack.readVal(address)
              stack.setValue(currentVal.asInstanceOf[Int] + 1, Address(address))
              context.stack.push(stack.readVal(address))
            })
          case `op_prefixDecr` =>
            resolveVar(context.stack.pop, (address) => {
              val currentVal = stack.readVal(address)
              stack.setValue(currentVal.asInstanceOf[Int] - 1, Address(address))
              context.stack.push(stack.readVal(address))
            })
          case `op_sizeof` =>
            context.stack.pop match {
              case VarRef(name) =>
                context.stack.push(context.vars.resolveId(name).sizeof)
            }
          case `op_amper` =>
            context.stack.pop match {
              case VarRef(name) =>
                context.stack.push(context.vars.resolveId(name).address)
            }
          case `op_star` =>
            context.stack.pop match {
              case VarRef(varName) =>
                val ptr = context.vars.resolveId(varName)
                val refAddress = Address(ptr.value.asInstanceOf[Int])
                context.stack.push(refAddress)
            }
          case `op_bracketedPrimary` => // not sure what this is for
        }
        Seq()
      }
    case lit: IASTLiteralExpression =>
      if (direction == Exiting) {
        //println("PUSHING LIT: " + castLiteral(lit))

        context.stack.push(Literal(lit.getRawSignature))

      }
      Seq()
    case id: IASTIdExpression =>
      if (direction == Exiting) {
        //println("PUSHING ID: " + id.getName.getRawSignature)
        context.stack.push(VarRef(id.getName.getRawSignature))
      }
      Seq()
    case typeExpr: IASTTypeIdExpression =>
      // used for sizeof calls on a type
      if (direction == Entering) {
        Seq(typeExpr.getTypeId)
      } else {
        Seq()
      }
    case call: IASTFunctionCallExpression =>
      FunctionCallExpr.parse(call, direction, context, stack)
    case bin: IASTBinaryExpression =>
      if (direction == Exiting) {

        if (context.vars.visited.contains(bin.getOperand2)) {
          val result = bin.getOperator match {
            case IASTBinaryExpression.op_assign =>
              var op2: Any = context.stack.pop
              var op1: Any = context.stack.pop
              BinaryExpr.parseAssign(op1, op2, context, stack)
            case _ => BinaryExpr.parse(bin, context, stack)
          }

          if (result != null) {
            context.stack.push(result)
          }
          Seq()
        } else {
          if (bin.getOperator == IASTBinaryExpression.op_assign) {
            context.parsingAssignmentDest = false
          }

          Seq(bin.getOperand2, bin)
        }
      } else {

        // We have to treat the destination op differently in an assignment

        if (bin.getOperator == IASTBinaryExpression.op_assign) {
          context.parsingAssignmentDest = true
        }
        Seq(bin.getOperand1)
      }
  }
}