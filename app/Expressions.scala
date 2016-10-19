package app.astViewer

import org.eclipse.cdt.core.dom.ast._

import scala.collection.mutable.{ ListBuffer, Stack }
import scala.util.control.Exception.allCatch
import java.util.Formatter;
import java.util.Locale;
import java.math.BigInteger
import org.eclipse.cdt.core.dom.ast.IBasicType.Kind._
import org.eclipse.cdt.internal.core.dom.parser.c.CBasicType
import org.eclipse.cdt.internal.core.dom.parser.c.CStructure
import org.eclipse.cdt.internal.core.dom.parser.c.CTypedef

object Expressions {

  def parse(expr: IASTExpression, direction: Direction, context: State, stack: State): Seq[IASTNode] = expr match {
    case ternary: IASTConditionalExpression =>
       if (direction == Entering) {
        Seq(ternary.getLogicalConditionExpression)
      } else {
        val result = context.stack.pop

        val value = result match {
          case VarRef(name) =>
            context.vars.resolveId(name).value
          case lit @ Literal(_) =>
            lit.cast
          case x => x
        }

        val conditionResult = value match {
          case x: Int     => x == 1
          case x: Boolean => x
        }
        if (conditionResult) {
          Seq(ternary.getPositiveResultExpression)
        } else {
          Seq(ternary.getNegativeResultExpression)
        }
      }
    case cast: IASTCastExpression =>
      if (direction == Entering) {
        Seq(cast.getOperand, cast.getTypeId)
      } else {
        val theType = context.stack.pop.asInstanceOf[IType]
        val addressInfo = context.stack.pop.asInstanceOf[AddressInfo]
        val refAddressInfo = AddressInfo(addressInfo.address, theType)
        context.stack.push(refAddressInfo)
        Seq()
      }
    case fieldRef: IASTFieldReference =>
      if (direction == Entering) {
        Seq(fieldRef.getFieldOwner)
      } else {
        
        var baseAddr: Address = Address(-1)
        val structType = context.stack.pop match {
          case VarRef(name) => 
            val struct = context.vars.resolveId(name)
            baseAddr = if (TypeHelper.isPointer(struct.theType)) {
              Address(struct.value.asInstanceOf[Int])
            } else {
              struct.address
            }
            if (struct.theType.isInstanceOf[CStructure]) {
              struct.theType.asInstanceOf[CStructure]
            } else {
              struct.theType.asInstanceOf[IPointerType].getType.asInstanceOf[CStructure]
            }  
          case AddressInfo(addr, theType) => 
            baseAddr = addr
            theType match {
              case typedef: CTypedef => typedef.getType.asInstanceOf[CStructure]
              case struct: CStructure => struct
              //case basic: IBasicType => println(basic); null
            }
        }    
        
        var offset = 0
        var resultAddress: AddressInfo = null
        structType.getFields.foreach{field =>
          if (field.getName == fieldRef.getFieldName.getRawSignature) {
            // can assume names are unique
            resultAddress = AddressInfo(baseAddr + offset, field.getType)
          } else {
            offset += TypeHelper.sizeof(field.getType)
          }
        }

        context.stack.push(resultAddress)        
        Seq()
      }
    case subscript: IASTArraySubscriptExpression =>
      if (direction == Entering) {
        Seq(subscript.getArgument, subscript.getArrayExpression)
      } else {
        
        if (!subscript.getArrayExpression.isInstanceOf[IASTArraySubscriptExpression]) {

          val arrayVarPtr: AddressInfo = context.stack.pop match {
            case VarRef(name) => 
              val variable = context.vars.resolveId(name)
              AddressInfo(variable.address, variable.theType)
            case addr @ AddressInfo(_, theType) => addr
          }
          
          val indexes = new ListBuffer[Int]()
          var itr: IASTNode = subscript
          while (itr.isInstanceOf[IASTArraySubscriptExpression]) {
            indexes += (context.stack.pop match {
              case VarRef(indexVarName) =>
                context.vars.resolveId(indexVarName).value
              case lit @ Literal(_) => lit.cast
              case x: Int =>
                x
            }).asInstanceOf[Int]
            itr = itr.getParent
          }
          
          val dimensions = new ListBuffer[Int]()
          var typeItr: IType = arrayVarPtr.theType
          while (typeItr.isInstanceOf[IArrayType]) {
            dimensions += typeItr.asInstanceOf[IArrayType].getSize.numericalValue.toInt
            typeItr = typeItr.asInstanceOf[IArrayType].getType
          }
          
          val intType = new CBasicType(IBasicType.Kind.eInt , 0) 
  
          val arrayAddress = stack.readVal(arrayVarPtr.address.value, intType).asInstanceOf[Int]
  
          val ancestors = Utils.getAncestors(subscript)
          
          // We have to treat the destination op differently in an assignment
          val isParsingAssignmentDest = ancestors.find{ _.isInstanceOf[IASTBinaryExpression]}.map { binary =>
            val bin = binary.asInstanceOf[IASTBinaryExpression]
            bin.getOperator == IASTBinaryExpression.op_assign
          }.getOrElse(false)
          
          var offset = 0

          indexes.zipWithIndex.foreach{ case (arrayIndex, index) =>
            offset += arrayIndex * dimensions.slice(0, index).reduceOption{_ * _}.getOrElse(1)
          }

          val elementAddress = Address(arrayAddress) + offset * TypeHelper.sizeof(arrayVarPtr.theType)
  
          if (isParsingAssignmentDest) {
            context.stack.push(AddressInfo(elementAddress, TypeHelper.resolve(arrayVarPtr.theType)))
          } else {
            context.stack.push(stack.readVal(elementAddress.value, TypeHelper.resolve(arrayVarPtr.theType)))
          }
        }

        Seq()
      }
    case unary: IASTUnaryExpression =>
      import org.eclipse.cdt.core.dom.ast.IASTUnaryExpression._

      def resolveVar(variable: Any, func: (Int, IBasicType) => Unit) = {
        variable match {
          case VarRef(name) =>
            val variable = context.vars.resolveId(name)
            func(variable.address.value, TypeHelper.resolve(variable.theType))
          case AddressInfo(addy, theType) => func(addy.value, TypeHelper.resolve(theType))
          case int: Int          => int
        }
      }

      if (direction == Entering) {
        Seq(unary.getOperand)
      } else {
        unary.getOperator match {
          case `op_minus` =>

            
            def negativeResolver(variable: RuntimeVariable) = {
              
              val info = AddressInfo(variable.address, variable.theType)
              
              resolveVar(info, (address, theType) => {
                val basicType = theType.asInstanceOf[IBasicType]
                context.stack.push(basicType.getKind match {
                  case `eInt`    => -stack.readVal(address, basicType).asInstanceOf[Int]
                  case `eDouble` => -stack.readVal(address, basicType).asInstanceOf[Double]
                })
            })}

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
            resolveVar(context.stack.pop, (address, theType) => {
              val currentVal = stack.readVal(address, theType)
              context.stack.push(currentVal)
              
              currentVal match {
                case int: Int => stack.setValue(int + 1, AddressInfo(Address(address), theType))
                case char: Char => stack.setValue(char + 1, AddressInfo(Address(address), theType))
              }
            })
          case `op_postFixDecr` =>
            resolveVar(context.stack.pop, (address, theType) => {
              val currentVal = stack.readVal(address, theType)
              context.stack.push(currentVal)
              
              currentVal match {
                case int: Int => stack.setValue(int - 1, AddressInfo(Address(address), theType))
                case char: Char => stack.setValue(char - 1, AddressInfo(Address(address), theType))
              }
              
            })
          case `op_prefixIncr` =>
            resolveVar(context.stack.pop, (address, theType) => {
              val currentVal = stack.readVal(address, theType)

              currentVal match {
                case int: Int => stack.setValue(int + 1, AddressInfo(Address(address), theType))
                case char: Char => stack.setValue(char + 1, AddressInfo(Address(address), theType))
              }
              
              context.stack.push(stack.readVal(address, theType))
            })
          case `op_prefixDecr` =>
            resolveVar(context.stack.pop, (address, theType) => {
              val currentVal = stack.readVal(address, theType)

              currentVal match {
                case int: Int => stack.setValue(int - 1, AddressInfo(Address(address), theType))
                case char: Char => stack.setValue(char - 1, AddressInfo(Address(address), theType))
              }
              
              context.stack.push(stack.readVal(address, theType))
            })
          case `op_sizeof` =>
            context.stack.pop match {
              case VarRef(name) =>
                context.stack.push(context.vars.resolveId(name).sizeof)
              case char: Char => context.stack.push(1)
              case int: Int => context.stack.push(4)
              case short: Short => context.stack.push(2)
              case long: Long => context.stack.push(8)
              case float: Float => context.stack.push(4)
              case double: Double => context.stack.push(8)
            }
          case `op_amper` =>
            context.stack.pop match {
              case VarRef(name) =>
                val variable = context.vars.resolveId(name)
                val info = AddressInfo(variable.address, variable.theType)
                context.stack.push(info)
            }
          case `op_star` =>
            context.stack.pop match {
              case VarRef(varName) =>       
                val ptr = context.vars.resolveId(varName)
                val refAddressInfo = AddressInfo(Address(ptr.value.asInstanceOf[Int]), TypeHelper.resolve(ptr.theType))
                context.stack.push(refAddressInfo)
              case address @ AddressInfo(addr, theType) =>

                unary.getChildren.head match {
                  case unary: IASTUnaryExpression if unary.getOperator == op_star => 
                    // nested pointers
                    val refAddressInfo = AddressInfo(Address(stack.readVal(addr.value, TypeHelper.resolve(theType)).asInstanceOf[Int]), theType)
                    context.stack.push(refAddressInfo)
                  case _ => 
                    context.stack.push(stack.readVal(addr.value, TypeHelper.resolve(theType)))
                }

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
        val theType = context.stack.pop.asInstanceOf[IBasicType]
        context.stack.push(TypeHelper.sizeof(theType))
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
          Seq(bin.getOperand2, bin)
        }
      } else {
        Seq(bin.getOperand1)
      }
  }
}