package scala.c.engine
package ast

import org.eclipse.cdt.core.dom.ast._
import org.eclipse.cdt.internal.core.dom.parser.c.CStructure

import scala.c.engine.ast.Declarator.assign
import scala.collection.mutable.{ListBuffer, Stack}

object Statement {

  def parse(statement: IASTStatement)(implicit state: State): Unit = statement match {
    case _: IASTNullStatement =>
      PartialFunction.empty
    case ret: IASTReturnStatement => {
      if (ret.getReturnValue != null) {
        val returnVal = Expressions.evaluate(ret.getReturnValue).head
        val functionScope = state.getFunctionScope

        val retVal = returnVal match {
          case structure @ LValue(addr, struct: CStructure) =>
            structure
          case info @ LValue(_, _) =>
            TypeHelper.cast(functionScope.returnType, info.rValue.value)
          case value @ RValue(_, _) if functionScope.returnType != null =>
            TypeHelper.cast(functionScope.returnType, value.value)
          case value @ RValue(_, _) => value
        }

        state.context.pushOntoStack(retVal)
      }

      throw ReturnFromFunction()
    }
    case decl: IASTDeclarationStatement =>
      Ast.step(decl.getDeclaration)
    case exprStatement: IASTExpressionStatement => {
      Expressions.evaluate(exprStatement.getExpression)
    }
  }
}
