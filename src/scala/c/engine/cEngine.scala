package scala.c.engine

import org.eclipse.cdt.core.dom.ast.{IASTCaseStatement, IASTDeclarationStatement, IASTEqualsInitializer, _}

import scala.collection.mutable.ListBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util

import org.eclipse.cdt.core.dom.ast.IASTBinaryExpression.op_assign
import org.eclipse.cdt.internal.core.dom.parser.c._

import scala.c.engine.ast.{Declarator, Expressions}


object Interpreter {
  implicit class CounterSC(val sc: StringContext) extends AnyVal {

    // Define functions that we want to use with string interpolation syntax
    def c(args: Any*)(implicit state: State): Unit = {
      Gcc.runCode(sc.parts.iterator.next, state, Iterator())
    }

    def func(args: Any*)(implicit state: State): Unit = {
      Gcc.runGlobalCode(sc.parts.iterator.next, state, List())
    }
  }
}

class Memory(size: Int) {

  import org.eclipse.cdt.core.dom.ast.IBasicType.Kind._

  var insertIndex = 0
  // turing tape
  val tape = ByteBuffer.allocateDirect(size)
  tape.order(ByteOrder.LITTLE_ENDIAN)

  def clearMemory(startingAddress: Int, numBytes: Int) = {
    var address = startingAddress
    for (i <- 0 until numBytes) {
      tape.put(address, 0.toByte)
      address += 1
    }
  }

  // use Address type to prevent messing up argument order
  def writeToMemory(newVal: AnyVal, address: Int, theType: IType, bitOffset: Int = 0, sizeInBits: Int = 0): Unit = {

    TypeHelper.stripSyntheticTypeInfo(theType) match {
      case basic: IBasicType if basic.getKind == eInt && basic.isShort =>
          newVal match {
            case int: Int => tape.putShort(address, int.asInstanceOf[Short])
            case short: Short => tape.putShort(address, short)
          }
      case basic: IBasicType if basic.getKind == eInt && basic.isLongLong =>
        newVal match {
          case long: Long => tape.putLong(address, long)
        }
      case basic: IBasicType if basic.getKind == eInt && basic.isLong =>
        newVal match {
          case int: Int => tape.putInt(address, int)
          case long: Long => tape.putInt(address, long.toInt)
        }
      case enum: CEnumeration =>
        newVal match {
          case int: Int => tape.putInt(address, int)
          case long: Long => tape.putInt(address, long.toInt)
        }
      case basic: IBasicType if basic.getKind == eInt || basic.getKind == eVoid =>
        newVal match {
          case int: Int =>
            val x = if (bitOffset != 0) {
              val currentVal = tape.getInt(address)
              val right = currentVal << (32 - bitOffset) >>> (32 - bitOffset)
              val left = currentVal >>> (sizeInBits + bitOffset) << (sizeInBits + bitOffset)

              val newVal = int << bitOffset
              left + newVal + right
            } else {
              int
            }

            tape.putInt(address, x)
          case long: Long => tape.putInt(address, long.toInt)
        }
      case basic: IBasicType if basic.getKind == eDouble =>
        newVal match {
          case double: Double => tape.putDouble(address, double)
        }
      case basic: IBasicType if basic.getKind == eFloat =>
        newVal match {
          case float: Float => tape.putFloat(address, float)
        }
      case basic: IBasicType if basic.getKind == eChar =>
        newVal match {
          case char: char => tape.put(address, char)
          case int: Int => tape.put(address, int.toByte)
        }
      case basic: IBasicType if basic.getKind == eBoolean =>
        tape.putInt(address, newVal.asInstanceOf[Int])
      case _: IFunctionType =>
        writePointerToMemory(newVal, address)
      case _: CStructure =>
        writePointerToMemory(newVal, address)
      case _: IPointerType =>
        writePointerToMemory(newVal, address)
      case _: IArrayType =>
        writePointerToMemory(newVal, address)
    }
  }

  private def writePointerToMemory(newVal: AnyVal, address: Int) = {
    newVal match {
      case int: Int => tape.putInt(address, int)
      case long: Long => tape.putInt(address, long.toInt)
    }
  }

  def readFromMemory(address: Int, theType: IType, bitOffset: Int = 0, sizeInBits: Int = 0): RValue = {
    theType match {
      case basic: IBasicType =>
        val res = if (basic.getKind == eInt && basic.isShort) {
          val result = tape.getShort(address)
          (result << (16 - sizeInBits - bitOffset) >>> (16 - sizeInBits)).toShort
        } else if (basic.getKind == eInt && basic.isLongLong) {
          val result = tape.getLong(address)
          result << (64 - sizeInBits - bitOffset) >>> (64 - sizeInBits)
        } else if (basic.getKind == eInt || basic.getKind == eBoolean) {
          val result = tape.getInt(address)
          result << (32 - sizeInBits - bitOffset) >>> (32 - sizeInBits)
        } else if (basic.getKind == eDouble) {
          tape.getDouble(address)
        } else if (basic.getKind == eFloat) {
          tape.getFloat(address)
        } else if (basic.getKind == eChar) {
          tape.get(address) // a C 'char' is a Java 'byte'
        }

        TypeHelper.castSign(theType, res)
      case typedef: CTypedef => readFromMemory(address, typedef.getType)
      case _ => RValue(tape.getInt(address), theType)
    }
  }
}

case class ReturnFromFunction() extends Exception("returning")

case class CachedRValue(expr2: IASTExpression) {
  var cachedValue: RValue = null
}

case class JmpIfNotEqual(expr: IASTExpression, relativeJump: Int)
case class JmpToLabelIfNotZero(expr: IASTExpression, label: Label)
case class JmpToLabelIfZero(expr: IASTExpression, label: Label)
case class JmpToLabelIfEqual(expr1: IASTExpression, expr2: CachedRValue, label: Label)
case class Jmp(relativeJump: Int)
case class JmpLabel(label: Label)
case class JmpName(label: String) {
  var destAddress = 0
}

abstract class Label {
  var address = 0
}

case class PushVariableStack()
case class PopVariableStack()

case class GotoLabel(name: String) extends Label
case class BreakLabel() extends Label
case class ContinueLabel() extends Label

case class CaseLabel(caseStatement: IASTCaseStatement) extends Label
case class DefaultLabel(default: IASTDefaultStatement) extends Label


object State {
  def flattenNode(tUnit: IASTNode)(implicit state: State): List[Any] = {

    def recurse(node: IASTNode): List[Any] = {

      node match {
        case ifStatement: IASTIfStatement =>
          val contents = PushVariableStack() +: recurse(ifStatement.getThenClause) :+ PopVariableStack()
          val elseContents = PushVariableStack() +: List(Option(ifStatement.getElseClause)).flatten.flatMap(recurse) :+ PopVariableStack()

          val jmp = if (ifStatement.getElseClause != null) {
            List(Jmp(elseContents.size))
          } else {
            List()
          }

          val all = contents ++ jmp

          // add +1 for the jmp statement
          JmpIfNotEqual(ifStatement.getConditionExpression, all.size) +: (all ++ elseContents)
        case forStatement: IASTForStatement =>

          val breakLabel = BreakLabel()
          state.breakLabelStack = breakLabel +: state.breakLabelStack
          val continueLabel = ContinueLabel()
          state.continueLabelStack = continueLabel +: state.continueLabelStack

          val init = List(forStatement.getInitializerStatement)
          val contents = recurse(forStatement.getBody)
          val iter = forStatement.getIterationExpression
          val beginLabel = new GotoLabel("")

          state.breakLabelStack = state.breakLabelStack.tail
          state.continueLabelStack = state.continueLabelStack.tail

          val execution = contents ++ (if (iter != null) List(continueLabel, iter) else List(continueLabel))

          val result = (if (forStatement.getConditionExpression != null) {
            init ++ (beginLabel +: JmpToLabelIfNotZero(forStatement.getConditionExpression, breakLabel) +: execution :+ JmpLabel(beginLabel)) :+ breakLabel
          } else {
            init ++ (beginLabel +: execution :+ JmpLabel(beginLabel)) :+ breakLabel
          })

          PushVariableStack() +: result :+ PopVariableStack()
        case whileStatement: IASTWhileStatement =>

          val breakLabel = BreakLabel()
          state.breakLabelStack = breakLabel +: state.breakLabelStack
          val continueLabel = ContinueLabel()
          state.continueLabelStack = continueLabel +: state.continueLabelStack

          val contents = recurse(whileStatement.getBody)
          val begin = new GotoLabel("")
          val end = new GotoLabel("")

          state.breakLabelStack = state.breakLabelStack.tail
          state.continueLabelStack = state.continueLabelStack.tail

          val result = List(JmpLabel(end), begin) ++ contents ++ List(end, continueLabel, JmpToLabelIfZero(whileStatement.getCondition, begin)) :+ breakLabel

          PushVariableStack() +: result :+ PopVariableStack()
        case doWhileStatement: IASTDoStatement =>

          val breakLabel = BreakLabel()
          state.breakLabelStack = breakLabel +: state.breakLabelStack
          val continueLabel = ContinueLabel()
          state.continueLabelStack = continueLabel +: state.continueLabelStack

          val contents = recurse(doWhileStatement.getBody)
          val begin = new Label{}

          state.breakLabelStack = state.breakLabelStack.tail
          state.continueLabelStack = state.continueLabelStack.tail

          val result = begin +: (contents ++ List(continueLabel, JmpToLabelIfZero(doWhileStatement.getCondition, begin), breakLabel))

          PushVariableStack() +: result :+ PopVariableStack()
        case switch: IASTSwitchStatement =>

          val breakLabel = BreakLabel()
          state.breakLabelStack = breakLabel +: state.breakLabelStack

          val descendants = recurse(switch.getBody)

          def getParentSwitchBody(node: IASTNode): IASTStatement = {
            if (node.getParent.isInstanceOf[IASTSwitchStatement]) {
              node.getParent.asInstanceOf[IASTSwitchStatement].getBody
            } else {
              getParentSwitchBody(node.getParent)
            }
          }

          val jumpTable = descendants.flatMap{
            case x @ CaseLabel(caseStatement) if (switch.getBody == getParentSwitchBody(caseStatement)) =>
              val cached = CachedRValue(switch.getControllerExpression)
              cached +: List(JmpToLabelIfEqual(caseStatement.getExpression, cached, x))
            case x @ DefaultLabel(default) if (switch.getBody == getParentSwitchBody(default)) =>
              List(JmpLabel(x))
            case _ =>
              List()
          } :+ JmpLabel(breakLabel)

          state.breakLabelStack = state.breakLabelStack.tail

          val result = jumpTable ++ descendants :+ breakLabel

          PushVariableStack() +: result :+ PopVariableStack()
        case x: IASTCaseStatement =>
          List(CaseLabel(x))
        case x: IASTDefaultStatement =>
          List(DefaultLabel(x))
        case _: IASTContinueStatement =>
          List(JmpLabel(state.continueLabelStack.head))
        case _: IASTBreakStatement =>
          List(JmpLabel(state.breakLabelStack.head))
        case _: IASTElaboratedTypeSpecifier =>
          List()
        case goto: IASTGotoStatement =>
          List(JmpName(goto.getName.toString))
        case fcn: IASTFunctionDefinition =>
          List(fcn)
        case compound: IASTCompoundStatement =>

          val isTypicalCompound = compound.getParent() match {
            case x: IASTSwitchStatement => true
            case x: CASTFunctionDefinition => true
            case x: CASTForStatement => true
            case x: CASTIfStatement => true
            case x: CASTDoStatement => true
            case x: CASTWhileStatement => true
            case _ => false
          }

          if (isTypicalCompound) {
            compound.getStatements.flatMap(recurse).toList
          } else {
            PushVariableStack() +: compound.getStatements.flatMap(recurse).toList :+ PopVariableStack()
          }
        case decl: IASTDeclarationStatement =>
          decl.getChildren.toList.flatMap(recurse)
        case decl: CASTSimpleDeclaration =>
          List(decl)
        case _: IASTSimpleDeclSpecifier =>
          List()
        case _: CASTTypedefNameSpecifier =>
          List()
        case decl: IASTDeclarator =>
          List(decl)
        case label: IASTLabelStatement =>
          GotoLabel(label.getName.toString) +: recurse(label.getNestedStatement)
        case exprState: CASTExpressionStatement =>
          List(exprState.getExpression)
        case _ =>
          //println("SPLITTING: " + node.getClass.getSimpleName + " : " + node.getRawSignature)
          node +: node.getChildren.toList
      }
    }

    tUnit.getChildren.flatMap{recurse}.toList
  }
}

abstract sealed trait NumBits
case object ThirtyTwoBits extends NumBits
case object SixtyFourBits extends NumBits

class State(pointerSize: NumBits) {

  object Stack extends Memory(500000)

  private var heapInsertIndex = 250000

  var functionContexts = List[FunctionScope]()
  def context: FunctionScope = functionContexts.head
  val functionList = new ListBuffer[Function]()
  val functionPointers = scala.collection.mutable.LinkedHashMap[String, Variable]()
  val stdout = new ListBuffer[Char]()

  private var breakLabelStack = List[Label]()
  private var continueLabelStack = List[Label]()

  var sources: List[IASTTranslationUnit] = null

  val pointerType = pointerSize match {
    case ThirtyTwoBits => TypeHelper.intType
    case SixtyFourBits => new CBasicType(IBasicType.Kind.eInt, IBasicType.IS_LONG_LONG)
  }

  def pushScope(scope: FunctionScope): Unit = {
    functionContexts = scope +: functionContexts
  }

  def getFunctionScope = {
    functionContexts.collect{case fcnScope: FunctionScope => fcnScope}.head
  }

  def parseGlobals(tUnits: List[IASTNode]) = {
    val program = new FunctionScope(List(), null, null) {}
    pushScope(program)
    program.init(tUnits, this, false)

    context.run(this) // parse globals

    context.setAddress(0)
  }

  def popFunctionContext = {
    Stack.insertIndex = functionContexts.head.startingStackAddr
    functionContexts = functionContexts.tail
  }

  def hasFunction(name: String): Boolean = functionList.exists{fcn => fcn.name == name}
  def getFunctionByIndex(index: Int): Function = functionList.find{fcn => fcn.index == index}.get

  Functions.scalaFunctions.foreach{fcn =>
    addScalaFunctionDef(fcn)
  }

  pushScope(new FunctionScope(List(), null, null) {})

  def init(codes: Seq[String], includePaths: List[String]): List[IASTNode] = {
    sources = codes.map{ code => Utils.getTranslationUnit(code, includePaths)}.toList

    sources.foreach { tUnit =>
      tUnit.getChildren.collect{case x:IASTFunctionDefinition => x}
        .filter(fcn => fcn.getDeclSpecifier.getStorageClass != IASTDeclSpecifier.sc_extern)
        .foreach { fcnDef =>
          addFunctionDef(fcnDef, fcnDef.getDeclarator.getName.toString == "main")
        }
    }

    sources
  }

  private def addScalaFunctionDef(fcn: Function) = {

    val count = functionPointers.size
    fcn.index = count

    functionList += fcn

    val fcnType = new CFunctionType(new CBasicType(IBasicType.Kind.eVoid, 0), null)
    val newVar = Variable(fcn.name, State.this, fcnType)
    Stack.writeToMemory(count, newVar.address, fcnType)

    functionPointers += fcn.name -> newVar
  }

  private def addStaticFunctionVars(node: IASTNode)(implicit state: State): List[Variable] = {
    node match {
      case decl: IASTDeclarator =>
        val nameBinding = decl.getName.resolveBinding()

        nameBinding match {
          case vari: IVariable =>
            if (vari.isStatic) {
              val theType = TypeHelper.stripSyntheticTypeInfo(nameBinding.asInstanceOf[IVariable].getType)

              val variable = Variable(decl.getName.toString, state, vari.getType)
              if (decl.getInitializer != null) {
                val initVals = Declarator.getRValues(decl.getInitializer.asInstanceOf[IASTEqualsInitializer].getInitializerClause, theType)
                Declarator.assign(variable, initVals, null, op_assign)
              }

              variable.isInitialized = true

              List(variable)
            } else {
              List()
            }
          case _ => List()
        }
      case x => x.getChildren.toList.flatMap{x => addStaticFunctionVars(x)}
    }
  }

  private def addFunctionDef(fcnDef: IASTFunctionDefinition, isMain: Boolean) = {
    val name = fcnDef.getDeclarator.getName
    val count = functionPointers.size

    val fcnType = fcnDef.getDeclarator.getName.resolveBinding().asInstanceOf[IFunction].getType

    functionList += new Function(name.toString, true) {
      index = count
      node = fcnDef
      override val staticVars = addStaticFunctionVars(fcnDef)(State.this)
      def run(formattedOutputParams: Array[RValue], state: State): Option[RValue] = {
        None
      }
    }

    if (!isMain) {
      val newVar = Variable(name.toString, State.this, fcnType)
      Stack.writeToMemory(count, newVar.address, fcnType)

      functionPointers += name.toString -> newVar
    }
  }

  def callFunctionFromScala(name: String, args: Array[RValue]): Seq[IASTNode] = {
    functionList.find(_.name == name).map { fcn =>
      // this is a function simulated in scala
      fcn.run(args.reverse, this).foreach(context.pushOntoStack)
    }

    Seq()
  }

  def callTheFunction(name: String, call: IASTFunctionCallExpression, scope: Option[FunctionScope], isApi: Boolean = false)(implicit state: State): Option[ValueType] = {
    functionList.find(_.name == name).flatMap{ function =>

      if (!function.isNative) {
        // this is a function simulated in scala

        val stackPos = Stack.insertIndex
        val args = call.getArguments.map{x => Expressions.evaluate(x)}

        val resolvedArgs: Array[RValue] = args.flatten.map{ TypeHelper.resolve }

        val returnVal = function.run(resolvedArgs.reverse, this)
        Stack.insertIndex = stackPos // pop the stack

        returnVal.map{ rVal =>
          rVal match {
            case file @ FileRValue(_) => println("RETURNING FILE: "); file
            case rValue => RValue(rValue.value, TypeHelper.unsignedIntType)
          }
        }
      } else {
        if (function.name == "main" && isApi) {
          scope.get.init(List(function.node), this, !scope.isDefined)
          functionContexts = List(scope.get)
          context.run(this)
          None
        } else {

          val newScope = scope.getOrElse {
            val expressionType = call.getExpressionType
            new FunctionScope(function.staticVars, functionContexts.headOption.getOrElse(null), expressionType)
          }

          newScope.init(List(function.node), this, !scope.isDefined)

          val args: List[ValueType] = call.getArguments.map { x => Expressions.evaluate(x).head }.toList

          val resolvedArgs = args.map{ TypeHelper.resolve }

          // printf assumes all floating point numbers are doubles
          val promoted = resolvedArgs.map{arg =>
            if (arg.theType.isInstanceOf[IBasicType] && arg.theType.asInstanceOf[IBasicType].getKind == IBasicType.Kind.eFloat) {
              TypeHelper.cast(TypeHelper.doubleType, arg.value)
            } else {
              arg
            }
          }

          newScope.pushOntoStack(promoted)
          newScope.pushOntoStack(RValue(resolvedArgs.size, TypeHelper.unsignedIntType))

          functionContexts = newScope +: functionContexts

          newScope.run(this)
          newScope.getReturnValue.map{ retVal =>
            val valuesToPush: Option[Array[Byte]] = retVal match {
              case structure @ LValue(_, _: CStructure) =>
                Some(structure.toByteArray)
              case _ => None
            }

            popFunctionContext

            valuesToPush.foreach { byteArray =>
              val newAddr = state.allocateSpace(byteArray.size)
              state.writeDataBlock(byteArray, newAddr)
            }

            retVal
          }.orElse{
            popFunctionContext
            None
          }
        }
      }
    }
  }

  def allocateSpace(numBytes: Int): Int = {
    val result = Stack.insertIndex
    Stack.insertIndex += Math.max(0, numBytes)
    result
  }

  def allocateHeapSpace(numBytes: Int): Int = {
    val result = heapInsertIndex
    heapInsertIndex += Math.max(0, numBytes)
    result
  }

  def copy(dst: Int, src: Int, numBytes: Int) = {
    Stack.tape.mark()
    Stack.tape.position(src)
    val array = new Array[Byte](numBytes)
    Stack.tape.get(array)
    Stack.tape.position(dst)
    Stack.tape.put(array)
    Stack.tape.reset()
  }

  def set(dst: Int, value: Byte, numBytes: Int) = {
    val array = new Array[Byte](numBytes)
    util.Arrays.fill(array, value)
    Stack.tape.mark()
    Stack.tape.position(dst)
    Stack.tape.put(array)
    Stack.tape.reset()
  }

  def readPtrVal(address: Int): Int = {
    Stack.tape.getInt(address)
  }

  def getString(str: String): RValue = {
    val theStr = Utils.stripQuotes(str)

    val withNull = (theStr.toCharArray() :+ 0.toChar).map(_.toByte) // terminating null char
    val strAddr = allocateSpace(withNull.size)

    writeDataBlock(withNull, strAddr)(this)
    RValue(strAddr, pointerType)
  }

  def createStringArrayVariable(varName: String, str: String): Variable = {
    val theStr = Utils.stripQuotes(str)
    val translateLineFeed = theStr.replace("\\n", 10.asInstanceOf[Char].toString)
    val withNull = (translateLineFeed.toCharArray() :+ 0.toChar)
      .map { char => RValue(char.toByte, TypeHelper.charType)}.toList // terminating null char

    val inferredArrayType = new CArrayType(TypeHelper.charType)
    inferredArrayType.setModifier(new CASTArrayModifier(new CASTLiteralExpression(IASTLiteralExpression.lk_integer_constant, str.size.toString.toCharArray)))

    val theArrayPtr = context.addArrayVariable(varName, inferredArrayType, withNull)
    theArrayPtr
  }

  def writeDataBlock(array: List[RValue], startingAddress: Int)(implicit state: State): Unit = {
      var address = startingAddress

      array.foreach {
        case RValue(newVal, theType) =>
          Stack.writeToMemory(newVal, address, theType)
          address += TypeHelper.sizeof(theType)(state)
      }
  }

  def writeDataBlock(array: Array[Byte], startingAddress: Int)(implicit state: State): Unit = {
    Stack.tape.mark()
    Stack.tape.position(startingAddress)
    Stack.tape.put(array, 0, array.size)
    Stack.tape.reset
  }

  def readDataBlock(startingAddress: Int, length: Int)(implicit state: State): Array[Byte] = {
    val result = new Array[Byte](length)
    Stack.tape.mark()
    Stack.tape.position(startingAddress)
    Stack.tape.get(result, 0, length)
    Stack.tape.reset
    result
  }
}