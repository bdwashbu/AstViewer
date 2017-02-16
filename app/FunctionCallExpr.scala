package app.astViewer

import org.eclipse.cdt.core.dom.ast.IASTFunctionCallExpression

import org.eclipse.cdt.core.dom.ast._

import scala.collection.mutable.{ListBuffer, Stack}
import scala.util.control.Exception.allCatch
import java.util.Formatter;
import java.util.Locale;
import Functions._
import org.eclipse.cdt.internal.core.dom.parser.c.CBasicType
import org.eclipse.cdt.internal.core.dom.parser.c.CStructure
import scala.collection.immutable.HashMap

object FunctionCallExpr {
  
  val varArgs = new ListBuffer[Any]()
  
  def formatArgument(arg: Any)(implicit state: State): AnyVal = {
    arg match {
        case Variable(theInfo: Variable) => 
          val info = theInfo.info
          if (TypeHelper.isPointer(info.theType) && TypeHelper.getPointedType(info.theType).isInstanceOf[IBasicType] &&
              TypeHelper.getPointedType(info.theType).asInstanceOf[IBasicType].getKind == IBasicType.Kind.eChar) {
            Address(state.readVal(info.address, info.theType).value.asInstanceOf[Int])
          } else {
            state.readVal(info.address, info.theType).value
          }
        case addr @ Address(address) => addr
        case ValueInfo(theVal, _) =>             
          theVal
        case AddressInfo(address, theType) => 
          state.readVal(address, TypeHelper.resolve(theType)).value
        case bool: Boolean => if (bool) 1 else 0
        case int: Int => int
        case float: Float => float
        case short: Short => short
        case long: Long => long
        case doub: Double => doub
        case char: Character => char
        case lit @ Literal(_) => lit.cast.value
        case StringLiteral(str) => 
          val strAddr = state.createStringVariable(str)
          strAddr
      }
  }
  
  def parse(call: IASTFunctionCallExpression, direction: Direction)(implicit state: State): Seq[IASTNode] = {
    if (direction == Exiting) {
        val name = state.stack.pop match {
          case VarRef(str) => str
          case AddressInfo(addr, theType) => state.getFunctionByIndex(state.readPtrVal(addr)).name
        }

        val fcnDef = if (state.hasFunction(name)) {
          state.getFunction(name)
        } else {
          val theVar = state.functionContexts.head.varMap(name)
          state.getFunctionByIndex(theVar.value.value.asInstanceOf[Int])
        }

        val argStack = new Stack[IType]()
        fcnDef.parameters.foreach{ arg =>
          argStack.push(arg)
        }
        
        val argList = call.getArguments.map { arg => (if (!argStack.isEmpty) argStack.pop else null, state.stack.pop) }.toArray

        var startingAddress: Address = Address(-1)

        argList.foreach { case (paramType, value) => 
          
          val result = formatArgument(value)
          
          if (paramType != null) {
            val argumentSpace = Variable.allocateSpace(state, paramType, 1)
            
            if (startingAddress == Address(-1)) {
              startingAddress = argumentSpace
            }
            
            state.setValue(result, argumentSpace)
          }
          
          // hacky: special case for var-args in scala function
          if (name == "printf") {
            state.stack.push(result)
          }
          
        }

        state.callTheFunction(name, call, startingAddress, call.getArguments.size)

      } else {
        call.getArguments.reverse ++ Seq(call.getFunctionNameExpression)
      }
  }
}