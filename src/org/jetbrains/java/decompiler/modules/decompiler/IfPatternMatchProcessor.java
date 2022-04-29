package org.jetbrains.java.decompiler.modules.decompiler;

import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.FunctionExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.VarExprent;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.SSAUConstructorSparseEx;
import org.jetbrains.java.decompiler.modules.decompiler.stats.IfStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionEdge;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionNode;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionsGraph;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.util.VBStyleCollection;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

/**
 * Handles pattern matching for instanceof in statements.
 *
 * @author SuperCoder79 and Kroppeb
 */
public final class IfPatternMatchProcessor {
  public static boolean matchInstanceof(RootStatement root, StructMethod mt) {
    SSAUConstructorSparseEx ssau = new SSAUConstructorSparseEx();
    ssau.splitVariables(root, mt);
    final boolean res = matchInstanceofRec(root, root, null, ssau);
    StackVarsProcessor.setVersionsToNull(root);
    return res;
  }

  private static boolean matchInstanceofRec(Statement statement, RootStatement root, Statement next, SSAUConstructorSparseEx ssau) {
    boolean res = false;
    if (statement.type == Statement.TYPE_SEQUENCE) {
      VBStyleCollection<Statement, Integer> stats = statement.getStats();
      for (int i = 0; i < stats.size(); i++) {
        Statement stat = stats.get(i);
        if (matchInstanceofRec(stat, root, i + 1 < stats.size() ? stats.get(i + 1) : null, ssau)) {
          res = true;
        }
      }
    } else {
      for (Statement stat : statement.getStats()) {
        if (matchInstanceofRec(stat, root, null, ssau)) {
          res = true;
        }
      }
    }

    if (statement instanceof IfStatement) {
      res |= handleIf((IfStatement) statement, root, next, ssau);
    }

    return res;
  }

  private static boolean handleIf(IfStatement statement, RootStatement root, Statement next, SSAUConstructorSparseEx ssau) {
    Exprent condition = statement.getHeadexprent().getCondition();

    if (condition.type != Exprent.EXPRENT_FUNCTION) {
      return false;
    }

    FunctionExprent func = (FunctionExprent) condition;

    boolean inverted;

    if (func.getFuncType() == FunctionExprent.FUNCTION_BOOL_NOT && func.getLstOperands().get(0).type == Exprent.EXPRENT_FUNCTION) {
      func = (FunctionExprent) func.getLstOperands().get(0);
      inverted = true;
    } else {
      inverted = false;
    }

    // Check for instanceof
    if (func.getFuncType() != FunctionExprent.FUNCTION_INSTANCEOF) {
      return false;
    }

    assert func.getLstOperands().size() == 2;

    Exprent source = func.getLstOperands().get(0);
    Exprent target = func.getLstOperands().get(1);

    if (source.type != Exprent.EXPRENT_VAR) {
      return false;
    }

    VarExprent sourceVar = (VarExprent) source;

    Statement targetStat = inverted ? statement.getElsestat() : statement.getIfstat();

    if (statement.getElsestat() == null && statement.getIfstat() == null) {
      // if statement has no sub-statements, and we will need to see if we can use next as a target
      if (next == null) {
        return false;
      }

      // Check if the target branch is an edge, if so, we can't use it as a target
      if (inverted ? statement.getElseEdge() != null : statement.getIfEdge() != null) {
        return false;
      }

      // Check if the non-target branch is an edge, if so, we should be able to use next as a target
      if (inverted ? statement.getIfEdge() != null : statement.getElseEdge() != null) {
        targetStat = next;
      } else {
        return false;
      }
    }

    while (true) {
      if (targetStat == null) {
        return false;
      } else if (targetStat.getExprents() == null || targetStat.getExprents().isEmpty()) {
        targetStat = targetStat.getFirst();
      } else {
        break;
      }
    }

    Exprent first = targetStat.getExprents().get(0);

    // Check inside the if statement for a cast
    if (first.type != Exprent.EXPRENT_ASSIGNMENT) {
      return false;
    }

    // If it's an assignment, get both sides
    Exprent left = first.getAllExprents().get(0);
    Exprent right = first.getAllExprents().get(1);


    // Right side needs to be a cast function
    if (right.type != Exprent.EXPRENT_FUNCTION ||
        ((FunctionExprent) right).getFuncType() != FunctionExprent.FUNCTION_CAST) {
      return false;
    }

    Exprent casted = right.getAllExprents().get(0);
    Exprent castedType = right.getAllExprents().get(1);

    // Check if the exprent being cast is the exprent on the left side of the instanceof
    // Make sure the left-hand side is a variable and the cast matches the instanceof
    if (casted.type != Exprent.EXPRENT_VAR ||
        sourceVar.getVarVersionPair().var != ((VarExprent) casted).getVarVersionPair().var ||
        left.type != Exprent.EXPRENT_VAR ||
        !target.equals(castedType)) {
      return false;
    }

    // check if the "new" variable gets merged and used. If so, we can't convert to an instanceof pattern
    if (getUsedVersions(ssau, (VarExprent) left)) {
      return false;
    }

    // Add the exprent to the instanceof exprent and remove it from the inside of the if statement
    func.getLstOperands().add(2, left);
    targetStat.getExprents().remove(0);


    if (targetStat.getExprents().isEmpty()) {
      // targetStat.getSuccessorEdges(Statement.)
      IfHelper.fixIf(statement, next);
    }

    //statement.setPatternMatched(true);

    return true;
  }

  // Mostly a copy from StackVarsProcessor
  // returns `notdom`
  private static boolean getUsedVersions(SSAUConstructorSparseEx ssa, VarExprent exprent) {
    VarVersionPair var = new VarVersionPair(exprent);
    VarVersionsGraph ssu = ssa.getSsuVersions();
    VarVersionNode node = ssu.nodes.getWithKey(var);

    Set<VarVersionNode> setVisited = new HashSet<>();
    Set<VarVersionNode> setNotDoms = new HashSet<>();

    LinkedList<VarVersionNode> stack = new LinkedList<>();
    stack.add(node);

    while (!stack.isEmpty()) {
      VarVersionNode nd = stack.remove(0);
      setVisited.add(nd);

      for (VarVersionEdge edge : nd.succs) {
        VarVersionNode succ = edge.dest;

        if (!setVisited.contains(edge.dest)) {

          boolean isDominated = true;
          for (VarVersionEdge prededge : succ.preds) {
            if (!setVisited.contains(prededge.source)) {
              isDominated = false;
              break;
            }
          }

          if (isDominated) {
            stack.add(succ);
          } else {
            setNotDoms.add(succ);
          }
        }
      }
    }

    setNotDoms.removeAll(setVisited);

    return !setNotDoms.isEmpty();
  }
}
