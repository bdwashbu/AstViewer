package c.engine

import org.eclipse.cdt.core.dom.ast._

import scala.collection.mutable.{ListBuffer, Stack}
import org.eclipse.cdt.internal.core.dom.parser.c._

object Executor {

  // 'node' must be a IASTCaseStatement or a IASTDefaultStatement
  def processSwitch(node: IASTNode): Seq[IASTNode] = {
    val codeToRun = new ListBuffer[IASTNode]()

    val siblings = node.getParent.getChildren

    var isSelfFound = false
    siblings.foreach { sib =>
      if (sib == node) {
        isSelfFound = true
      } else if (isSelfFound && !sib.isInstanceOf[IASTCaseStatement]) {
        codeToRun += sib
      }
    }

    codeToRun
  }

  def step(current: IASTNode, direction: Direction)(implicit state: State): Seq[IASTNode] = {

    current match {
      case statement: IASTStatement =>
        Statement.parse(statement, direction)
      case expression: IASTExpression =>
        Expressions.parse(expression, direction)
      case array: IASTArrayModifier =>
        if (direction == Exiting) {
          Seq()
        } else {
          if (array.getConstantExpression != null) {
            Seq(array.getConstantExpression)
          } else {
            Seq()
          }
        }
      case ptr: IASTPointer =>
        Seq()
      case tUnit: IASTTranslationUnit =>
        tUnit.getChildren.filterNot{_.isInstanceOf[IASTFunctionDefinition]}
      case simple: IASTSimpleDeclaration =>
        if (direction == Entering) {
          val declSpec = simple.getDeclSpecifier
          if (declSpec.isInstanceOf[IASTEnumerationSpecifier]) {
            simple.getDeclarators :+ simple.getDeclSpecifier
          } else {
            simple.getDeclarators
          }

        } else {
          Seq()
        }
      case enumerator: CASTEnumerator =>
        if (direction == Entering) {
          Seq(enumerator.getValue)
        } else {
          val newVar = state.context.addVariable(enumerator.getName.getRawSignature, TypeHelper.pointerType)
          val value = state.stack.pop.asInstanceOf[RValue]
          state.setValue(value.value, newVar.address)
          Seq()
        }
      case enum: IASTEnumerationSpecifier =>
        if (direction == Exiting) {
          enum.getEnumerators
        } else {
          Seq()
        }
      case fcnDec: IASTFunctionDeclarator =>
        val isInFunctionPrototype = Utils.getAncestors(fcnDec).exists{_.isInstanceOf[IASTSimpleDeclaration]}

        // ignore main's params for now
        val isInMain = fcnDec.getName.getRawSignature == "main"
        val fcnName = fcnDec.getName.getRawSignature

        val paramDecls = new Stack[IASTParameterDeclaration]() ++ fcnDec.getChildren.collect{case x: IASTParameterDeclaration => x}

        if (!paramDecls.isEmpty && direction == Entering && !isInMain) {

          var numArgs = 0

          val others = fcnDec.getChildren.filter{x => !x.isInstanceOf[IASTParameterDeclaration] && !x.isInstanceOf[IASTName]}

          if (!isInFunctionPrototype) {
            numArgs = state.stack.pop.asInstanceOf[RValue].value.asInstanceOf[Integer]
            val args = (0 until numArgs).map{arg => state.stack.pop}.reverse

            val resolvedArgs = args.map{x =>
              Utils.allocateString(x, false)(state)
            }

            resolvedArgs.foreach{ arg =>
              if (!isInFunctionPrototype && !paramDecls.isEmpty) {

                val paramDecl = paramDecls.pop

                val paramInfo = paramDecl.getDeclarator.getName.resolveBinding().asInstanceOf[CParameter]

                val name = paramDecl.getDeclarator.getName.getRawSignature
                val newVar = state.context.addVariable(name, paramInfo.getType)
                val casted = TypeHelper.cast(newVar.theType, arg.value).value
                state.setValue(casted, newVar.address)
              } else {
                val theType = TypeHelper.getType(arg.value)
                val sizeof = TypeHelper.sizeof(theType)
                val space = state.allocateSpace(Math.max(sizeof, 4))
                state.setValue(arg.value, space)
              }
            }
          }

          others
        } else {
          Seq()
        }
      case decl: IASTDeclarator =>
        Declarator.execute(decl, direction)
      case fcnDef: IASTFunctionDefinition =>
        if (direction == Exiting) {
          if (!state.context.stack.isEmpty) {
            val retVal = state.context.stack.pop
            if (fcnDef.getDeclarator.getName.getRawSignature != "main") {
              state.popFunctionContext
            }
            state.context.stack.push(retVal)
          } else {
            if (fcnDef.getDeclarator.getName.getRawSignature != "main") {
              state.popFunctionContext
            }
          }
          Seq()
        } else {
          Seq(fcnDef.getDeclarator, fcnDef.getBody)
        }
      case eq: IASTEqualsInitializer =>
        if (direction == Entering) {
          Seq(eq.getInitializerClause)
        } else {
          Seq()
        }
      case initList: IASTInitializerList =>
        if (direction == Entering) {
          initList.getClauses
        } else {
          Seq()
        }
      case typeId: IASTTypeId =>
        if (direction == Exiting) {

          val result: TypeInfo = typeId.getDeclSpecifier match {
            case simple: IASTSimpleDeclSpecifier =>
              import org.eclipse.cdt.core.dom.ast.IASTSimpleDeclSpecifier._

              var config = 0

              if (simple.isLong) {
                config |= IBasicType.IS_LONG
              }

              if (simple.isUnsigned) {
                config |= IBasicType.IS_UNSIGNED
              } else {
                config |= IBasicType.IS_SIGNED
              }

              var result: IType = simple.getType match {
                case `t_unspecified` => new CBasicType(IBasicType.Kind.eInt, config)
                case `t_int`    => new CBasicType(IBasicType.Kind.eInt, config)
                case `t_float`  => new CBasicType(IBasicType.Kind.eFloat, config)
                case `t_double` => new CBasicType(IBasicType.Kind.eDouble, config)
                case `t_char`   => new CBasicType(IBasicType.Kind.eChar, config)
                case `t_void`   => new CBasicType(IBasicType.Kind.eVoid, config)
              }

              for (ptr <- typeId.getAbstractDeclarator.getPointerOperators) {
                result = new CPointerType(result, 0)
              }

              TypeInfo(result)

            case typespec: CASTTypedefNameSpecifier =>
              TypeInfo(typespec.getName.resolveBinding().asInstanceOf[IType])
            case elab: CASTElaboratedTypeSpecifier =>
              TypeInfo(elab.getName.resolveBinding().asInstanceOf[CStructure])
          }

          state.stack.push(result)
        }
        Seq()
    }
  }

  def preload(codes: Seq[String], state: State) = {
    state.tUnit = Utils.getTranslationUnit(codes)
    state.current = state.tUnit

    val fcns = state.tUnit.getChildren.collect{case x:IASTFunctionDefinition => x}.filter(_.getDeclSpecifier.getStorageClass != IASTDeclSpecifier.sc_extern)
    fcns.foreach{fcnDef => state.addFunctionDef(fcnDef)}
  }

  def init(codes: Seq[String], reset: Boolean, state: State) = {
    preload(codes, state)
    run(state)

    state.context.pathStack.clear
    state.context.pathStack.push(state.getFunction("main").node)
    state.current = state.context.pathStack.head
  }

  def tick(state: State): Unit = {
    state.direction = if (state.context.visited.contains(state.current)) Exiting else Entering

    //println(state.current.getClass.getSimpleName + ":" + state.direction)
    
    val paths: Seq[IASTNode] = if (state.isGotoing) {
      Executor.step(state.current, Gotoing)(state)
    } else {
      Executor.step(state.current, state.direction)(state)
    }

    if (state.isReturning) {
      var last: IASTNode = null
      while (state.context.pathStack.size > 1 && !last.isInstanceOf[IASTFunctionDefinition]) {
        last = state.context.pathStack.pop
      }

      state.current = state.context.pathStack.head
      state.isReturning = false
    } else {

      if (state.direction == Exiting) {
        state.context.pathStack.pop
      } else {
        state.context.visited += state.current
      }
  
      paths.reverse.foreach { path => state.context.pathStack.push(path) }
  
      if (!state.context.pathStack.isEmpty) {
        state.current = state.context.pathStack.head
      } else {
        state.current = null
      }
    }
  }

  def run(state: State) = {
    while (state.current != null) {
      try {
        tick(state)
      } catch {
        case e =>
          println(state.current.getRawSignature)
          throw e
      }
    }
  }
}