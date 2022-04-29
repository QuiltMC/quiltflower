// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.modules.decompiler;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.collectors.CounterContainer;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.modules.decompiler.exps.*;
import org.jetbrains.java.decompiler.modules.decompiler.stats.IfStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.struct.gen.VarType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public final class SecondaryFunctionsHelper {

  private static final int[] funcsnot = new int[]{
    FunctionExprent.FUNCTION_NE,
    FunctionExprent.FUNCTION_EQ,
    FunctionExprent.FUNCTION_GE,
    FunctionExprent.FUNCTION_LT,
    FunctionExprent.FUNCTION_LE,
    FunctionExprent.FUNCTION_GT,
    FunctionExprent.FUNCTION_COR,
    FunctionExprent.FUNCTION_CADD
  };

  private static final HashMap<Integer, Integer[]> mapNumComparisons = new HashMap<>();

  static {
    mapNumComparisons.put(FunctionExprent.FUNCTION_EQ,
                          new Integer[]{FunctionExprent.FUNCTION_LT, FunctionExprent.FUNCTION_EQ, FunctionExprent.FUNCTION_GT});
    mapNumComparisons.put(FunctionExprent.FUNCTION_NE,
                          new Integer[]{FunctionExprent.FUNCTION_GE, FunctionExprent.FUNCTION_NE, FunctionExprent.FUNCTION_LE});
    mapNumComparisons.put(FunctionExprent.FUNCTION_GT, new Integer[]{FunctionExprent.FUNCTION_GE, FunctionExprent.FUNCTION_GT, null});
    mapNumComparisons.put(FunctionExprent.FUNCTION_GE, new Integer[]{null, FunctionExprent.FUNCTION_GE, FunctionExprent.FUNCTION_GT});
    mapNumComparisons.put(FunctionExprent.FUNCTION_LT, new Integer[]{null, FunctionExprent.FUNCTION_LT, FunctionExprent.FUNCTION_LE});
    mapNumComparisons.put(FunctionExprent.FUNCTION_LE, new Integer[]{FunctionExprent.FUNCTION_LT, FunctionExprent.FUNCTION_LE, null});
  }

  public static boolean identifySecondaryFunctions(Statement stat, VarProcessor varProc) {
    if (stat.getExprents() == null) {
      // if(){;}else{...} -> if(!){...}
      if (stat.type == Statement.TYPE_IF) {
        IfStatement ifelsestat = (IfStatement)stat;
        Statement ifstat = ifelsestat.getIfstat();

        if (ifelsestat.iftype == IfStatement.IFTYPE_IFELSE && ifstat.getExprents() != null &&
            ifstat.getExprents().isEmpty() && (ifstat.getAllSuccessorEdges().isEmpty() || !ifstat.getAllSuccessorEdges().get(0).explicit)) {

          // move else to the if position
          ifelsestat.getStats().removeWithKey(ifstat.id);

          ifelsestat.iftype = IfStatement.IFTYPE_IF;
          ifelsestat.setIfstat(ifelsestat.getElsestat());
          ifelsestat.setElsestat(null);

          if (ifelsestat.getAllSuccessorEdges().isEmpty() && !ifstat.getAllSuccessorEdges().isEmpty()) {
            StatEdge endedge = ifstat.getAllSuccessorEdges().get(0);

            ifstat.removeSuccessor(endedge);
            endedge.setSource(ifelsestat);
            if (endedge.closure != null) {
              ifelsestat.getParent().addLabeledEdge(endedge);
            }
            ifelsestat.addSuccessor(endedge);
          }

          ifelsestat.getFirst().removeSuccessor(ifelsestat.getIfEdge());

          ifelsestat.setIfEdge(ifelsestat.getElseEdge());
          ifelsestat.setElseEdge(null);

          // negate head expression
          ifelsestat.setNegated(!ifelsestat.isNegated());
          ifelsestat.getHeadexprentList().set(0, ((IfExprent)ifelsestat.getHeadexprent().copy()).negateIf());

          return true;
        }
      }
    }

    boolean ret = false;
    boolean replaced = true;
    while (replaced) {
      replaced = false;

      List<Object> lstObjects = new ArrayList<>(stat.getExprents() == null ? stat.getSequentialObjects() : stat.getExprents());

      for (int i = 0; i < lstObjects.size(); i++) {
        Object obj = lstObjects.get(i);

        if (obj instanceof Statement) {
          if (identifySecondaryFunctions((Statement)obj, varProc)) {
            ret = true;
            replaced = true;
            break;
          }
        }
        else if (obj instanceof Exprent) {
          Exprent retexpr = identifySecondaryFunctions((Exprent)obj, true, varProc);
          if (retexpr != null) {
            if(retexpr != obj) {
              if (stat.getExprents() == null) {
                // only head expressions can be replaced!
                stat.replaceExprent((Exprent) obj, retexpr);
              } else {
                stat.getExprents().set(i, retexpr);
              }
              replaced = true;
            }
            ret = true;
            break;
          }
        }
      }
    }

    return ret;
  }

  private static Exprent identifySecondaryFunctions(Exprent exprent, boolean statement_level, VarProcessor varProc) {
    if (exprent.type == Exprent.EXPRENT_FUNCTION) {
      FunctionExprent fexpr = (FunctionExprent)exprent;

      switch (fexpr.getFuncType()) {
        case FunctionExprent.FUNCTION_BOOL_NOT:

          Exprent retparam = propagateBoolNot(fexpr);

          if (retparam != null) {
            return retparam;
          }

          break;
        case FunctionExprent.FUNCTION_EQ:
        case FunctionExprent.FUNCTION_NE:
        case FunctionExprent.FUNCTION_GT:
        case FunctionExprent.FUNCTION_GE:
        case FunctionExprent.FUNCTION_LT:
        case FunctionExprent.FUNCTION_LE:
          Exprent expr1 = fexpr.getLstOperands().get(0);
          Exprent expr2 = fexpr.getLstOperands().get(1);

          if (expr1.type == Exprent.EXPRENT_CONST) {
            expr2 = expr1;
            expr1 = fexpr.getLstOperands().get(1);
          }

          if (expr1.type == Exprent.EXPRENT_FUNCTION && expr2.type == Exprent.EXPRENT_CONST) {
            FunctionExprent funcexpr = (FunctionExprent)expr1;
            ConstExprent cexpr = (ConstExprent)expr2;

            int functype = funcexpr.getFuncType();
            if (functype == FunctionExprent.FUNCTION_LCMP || functype == FunctionExprent.FUNCTION_FCMPG ||
                functype == FunctionExprent.FUNCTION_FCMPL || functype == FunctionExprent.FUNCTION_DCMPG ||
                functype == FunctionExprent.FUNCTION_DCMPL) {

              int desttype = -1;

              Integer[] destcons = mapNumComparisons.get(fexpr.getFuncType());
              if (destcons != null) {
                int index = cexpr.getIntValue() + 1;
                if (index >= 0 && index <= 2) {
                  Integer destcon = destcons[index];
                  if (destcon != null) {
                    desttype = destcon;
                  }
                }
              }

              if (desttype >= 0) {
                if (functype != FunctionExprent.FUNCTION_LCMP) {
                  boolean oneForNan = functype == FunctionExprent.FUNCTION_DCMPL || functype == FunctionExprent.FUNCTION_FCMPL;
                  boolean trueForOne = desttype == FunctionExprent.FUNCTION_LT || desttype == FunctionExprent.FUNCTION_LE;
                  boolean trueForNan = oneForNan == trueForOne;
                  if (trueForNan) {
                    List<Exprent> operands = new ArrayList<>();
                    operands.add(new FunctionExprent(funcsnot[desttype - FunctionExprent.FUNCTION_EQ],
                      funcexpr.getLstOperands(), funcexpr.bytecode));
                    return new FunctionExprent(FunctionExprent.FUNCTION_BOOL_NOT, operands, funcexpr.bytecode);
                  }
                }
                return new FunctionExprent(desttype, funcexpr.getLstOperands(), funcexpr.bytecode);
              }
            }
          }
      }
    }


    boolean replaced = true;
    boolean ret = false;
    while (replaced) {
      replaced = false;

      for (Exprent expr : exprent.getAllExprents()) {
        Exprent retexpr = identifySecondaryFunctions(expr, false, varProc);
        if (retexpr != null) {
          if(retexpr != expr) {
            exprent.replaceExprent(expr, retexpr);
            replaced = true;
          }
          ret = true;
          break;
        }
      }
    }

    switch (exprent.type) {
      case Exprent.EXPRENT_FUNCTION:
        FunctionExprent fexpr = (FunctionExprent)exprent;
        List<Exprent> lstOperands = fexpr.getLstOperands();

        switch (fexpr.getFuncType()) {
          case FunctionExprent.FUNCTION_XOR:
            for (int i = 0; i < 2; i++) {
              Exprent operand = lstOperands.get(i);
              VarType operandtype = operand.getExprType();

              if (operand.type == Exprent.EXPRENT_CONST &&
                  operandtype.type != CodeConstants.TYPE_BOOLEAN) {
                ConstExprent cexpr = (ConstExprent)operand;
                long val;
                if (operandtype.type == CodeConstants.TYPE_LONG) {
                  val = (Long)cexpr.getValue();
                }
                else {
                  val = (Integer)cexpr.getValue();
                }

                if (val == -1) {
                  List<Exprent> lstBitNotOperand = new ArrayList<>();
                  lstBitNotOperand.add(lstOperands.get(1 - i));
                  return new FunctionExprent(FunctionExprent.FUNCTION_BIT_NOT, lstBitNotOperand, fexpr.bytecode);
                }
              }
            }
            break;
          case FunctionExprent.FUNCTION_EQ:
          case FunctionExprent.FUNCTION_NE:
            if (lstOperands.get(0).getExprType().type == CodeConstants.TYPE_BOOLEAN &&
                lstOperands.get(1).getExprType().type == CodeConstants.TYPE_BOOLEAN) {
              for (int i = 0; i < 2; i++) {
                if (lstOperands.get(i).type == Exprent.EXPRENT_CONST) {
                  ConstExprent cexpr = (ConstExprent)lstOperands.get(i);
                  int val = (Integer)cexpr.getValue();

                  if ((fexpr.getFuncType() == FunctionExprent.FUNCTION_EQ && val == 1) ||
                      (fexpr.getFuncType() == FunctionExprent.FUNCTION_NE && val == 0)) {
                    return lstOperands.get(1 - i);
                  }
                  else {
                    List<Exprent> lstNotOperand = new ArrayList<>();
                    lstNotOperand.add(lstOperands.get(1 - i));
                    return new FunctionExprent(FunctionExprent.FUNCTION_BOOL_NOT, lstNotOperand, fexpr.bytecode);
                  }
                }
              }
            }
            break;
          case FunctionExprent.FUNCTION_BOOL_NOT:
            if (lstOperands.get(0).type == Exprent.EXPRENT_CONST) {
              int val = ((ConstExprent)lstOperands.get(0)).getIntValue();
              if (val == 0) {
                return new ConstExprent(VarType.VARTYPE_BOOLEAN, 1, fexpr.bytecode);
              }
              else {
                return new ConstExprent(VarType.VARTYPE_BOOLEAN, 0, fexpr.bytecode);
              }
            }
            break;
          case FunctionExprent.FUNCTION_IIF:
            Exprent expr0 = lstOperands.get(0);
            Exprent expr1 = lstOperands.get(1);
            Exprent expr2 = lstOperands.get(2);

            if (expr1.type == Exprent.EXPRENT_CONST && expr2.type == Exprent.EXPRENT_CONST) {
              ConstExprent cexpr1 = (ConstExprent)expr1;
              ConstExprent cexpr2 = (ConstExprent)expr2;

              if (cexpr1.getExprType().type == CodeConstants.TYPE_BOOLEAN &&
                  cexpr2.getExprType().type == CodeConstants.TYPE_BOOLEAN) {

                if (cexpr1.getIntValue() == 0 && cexpr2.getIntValue() != 0) {
                  return new FunctionExprent(FunctionExprent.FUNCTION_BOOL_NOT, lstOperands.get(0), fexpr.bytecode);
                }
                else if (cexpr1.getIntValue() != 0 && cexpr2.getIntValue() == 0) {
                  return lstOperands.get(0);
                }
              }
            } else if (DecompilerContext.getOption(IFernflowerPreferences.TERNARY_CONSTANT_SIMPLIFICATION)) {
              if (expr1.type == Exprent.EXPRENT_CONST && expr1.getExprType().type == CodeConstants.TYPE_BOOLEAN) {
                ConstExprent cexpr1 = (ConstExprent) expr1;
                boolean val = cexpr1.getIntValue() != 0;

                if (val) {
                  // bl ? true : bl2 <-> bl || bl2
                  return new FunctionExprent(FunctionExprent.FUNCTION_COR, Arrays.asList(lstOperands.get(0), lstOperands.get(2)), fexpr.bytecode);
                } else {
                  // bl ? false : bl2 <-> !bl && bl2
                  FunctionExprent fnot = new FunctionExprent(FunctionExprent.FUNCTION_BOOL_NOT, lstOperands.get(0), fexpr.bytecode);
                  return new FunctionExprent(FunctionExprent.FUNCTION_CADD, Arrays.asList(fnot, lstOperands.get(2)), fexpr.bytecode);
                }
              } else if (expr2.type == Exprent.EXPRENT_CONST && expr2.getExprType().type == CodeConstants.TYPE_BOOLEAN) {
                ConstExprent cexpr2 = (ConstExprent) expr2;
                boolean val = cexpr2.getIntValue() != 0;

                if (val) {
                  // bl ? bl2 : true <-> !bl || bl2
                  FunctionExprent fnot = new FunctionExprent(FunctionExprent.FUNCTION_BOOL_NOT, lstOperands.get(0), fexpr.bytecode);
                  return new FunctionExprent(FunctionExprent.FUNCTION_COR, Arrays.asList(fnot, lstOperands.get(1)), fexpr.bytecode);
                } else {
                  // bl ? bl2 : false <-> bl && bl2
                  return new FunctionExprent(FunctionExprent.FUNCTION_CADD, Arrays.asList(lstOperands.get(0), lstOperands.get(1)), fexpr.bytecode);
                }
              } else if (expr1.getExprType().type == CodeConstants.TYPE_BOOLEAN && expr1.equals(expr0)) {
                // bl ? bl : bl2 <-> bl || bl2
                return new FunctionExprent(FunctionExprent.FUNCTION_COR, Arrays.asList(lstOperands.get(0), lstOperands.get(2)), fexpr.bytecode);
              } else if (expr2.getExprType().type == CodeConstants.TYPE_BOOLEAN && expr2.equals(expr0)) {
                // bl ? bl2 : bl <-> bl && bl2
                return new FunctionExprent(FunctionExprent.FUNCTION_CADD, Arrays.asList(lstOperands.get(0), lstOperands.get(1)), fexpr.bytecode);
              }
            }
            break;
          case FunctionExprent.FUNCTION_LCMP:
          case FunctionExprent.FUNCTION_FCMPL:
          case FunctionExprent.FUNCTION_FCMPG:
          case FunctionExprent.FUNCTION_DCMPL:
          case FunctionExprent.FUNCTION_DCMPG:
            int var = DecompilerContext.getCounterContainer().getCounterAndIncrement(CounterContainer.VAR_COUNTER);
            VarType type = lstOperands.get(0).getExprType();

            FunctionExprent iff = new FunctionExprent(FunctionExprent.FUNCTION_IIF, Arrays.asList(
              new FunctionExprent(FunctionExprent.FUNCTION_LT, Arrays.asList(new VarExprent(var, type, varProc),
                ConstExprent.getZeroConstant(type.type)), null),
              new ConstExprent(VarType.VARTYPE_INT, -1, null),
              new ConstExprent(VarType.VARTYPE_INT, 1, null)), null);

            FunctionExprent head = new FunctionExprent(FunctionExprent.FUNCTION_EQ, Arrays.asList(
              new AssignmentExprent(new VarExprent(var, type, varProc),
                                    new FunctionExprent(FunctionExprent.FUNCTION_SUB, Arrays.asList(lstOperands.get(0), lstOperands.get(1)), null),
                                    null),
              ConstExprent.getZeroConstant(type.type)), null);

            varProc.setVarType(new VarVersionPair(var, 0), type);

            return new FunctionExprent(FunctionExprent.FUNCTION_IIF, Arrays.asList(
              head, new ConstExprent(VarType.VARTYPE_INT, 0, null), iff), fexpr.bytecode);
        }
        break;
      case Exprent.EXPRENT_ASSIGNMENT: // check for conditional assignment
        AssignmentExprent asexpr = (AssignmentExprent)exprent;
        Exprent right = asexpr.getRight();
        Exprent left = asexpr.getLeft();

        if (right.type == Exprent.EXPRENT_FUNCTION) {
          FunctionExprent func = (FunctionExprent)right;

          VarType midlayer = null;
          if (func.getFuncType() >= FunctionExprent.FUNCTION_I2L &&
              func.getFuncType() <= FunctionExprent.FUNCTION_I2S) {
            right = func.getLstOperands().get(0);
            midlayer = func.getSimpleCastType();
            if (right.type == Exprent.EXPRENT_FUNCTION) {
              func = (FunctionExprent)right;
            }
            else {
              return null;
            }
          }

          List<Exprent> lstFuncOperands = func.getLstOperands();

          Exprent cond = null;

          switch (func.getFuncType()) {
            case FunctionExprent.FUNCTION_ADD:
            case FunctionExprent.FUNCTION_AND:
            case FunctionExprent.FUNCTION_OR:
            case FunctionExprent.FUNCTION_XOR:
              if (left.equals(lstFuncOperands.get(1))) {
                cond = lstFuncOperands.get(0);
                break;
              }
            case FunctionExprent.FUNCTION_SUB:
            case FunctionExprent.FUNCTION_MUL:
            case FunctionExprent.FUNCTION_DIV:
            case FunctionExprent.FUNCTION_REM:
            case FunctionExprent.FUNCTION_SHL:
            case FunctionExprent.FUNCTION_SHR:
            case FunctionExprent.FUNCTION_USHR:
              if (left.equals(lstFuncOperands.get(0))) {
                cond = lstFuncOperands.get(1);
              }
          }

          if (cond != null && (midlayer == null || midlayer.equals(cond.getExprType()))) {
            asexpr.setRight(cond);
            asexpr.setCondType(func.getFuncType());
          }
        }
        break;
      case Exprent.EXPRENT_INVOCATION:
        if (!statement_level) { // simplify if exprent is a real expression. The opposite case is pretty absurd, can still happen however (and happened at least once).
          Exprent retexpr = ConcatenationHelper.contractStringConcat(exprent);
          if (!exprent.equals(retexpr)) {
            return retexpr;
          }
        }
    }

    if(ret){
      // return exprent to show that we have a nested change
      return exprent;
    } else {
      return null;
    }
  }

  public static Exprent propagateBoolNot(Exprent exprent) {

    if (exprent.type == Exprent.EXPRENT_FUNCTION) {
      FunctionExprent fexpr = (FunctionExprent)exprent;

      if (fexpr.getFuncType() == FunctionExprent.FUNCTION_BOOL_NOT) {

        Exprent param = fexpr.getLstOperands().get(0);

        if (param.type == Exprent.EXPRENT_FUNCTION) {
          FunctionExprent fparam = (FunctionExprent)param;

          int ftype = fparam.getFuncType();
          boolean canSimplify = false;
          switch (ftype) {
            case FunctionExprent.FUNCTION_BOOL_NOT:
              Exprent newexpr = fparam.getLstOperands().get(0);
              Exprent retexpr = propagateBoolNot(newexpr);
              return retexpr == null ? newexpr : retexpr;
            case FunctionExprent.FUNCTION_IIF:
              // Wrap branches
              FunctionExprent fex1 = new FunctionExprent(FunctionExprent.FUNCTION_BOOL_NOT, fparam.getLstOperands().get(1), null);
              FunctionExprent fex2 = new FunctionExprent(FunctionExprent.FUNCTION_BOOL_NOT, fparam.getLstOperands().get(2), null);

              // Propagate both branches
              Exprent ex1 = propagateBoolNot(fex1);
              Exprent ex2 = propagateBoolNot(fex2);

              // Set both branches to new version if it was created, or old if it wasn't
              fparam.getLstOperands().set(1, ex1 == null ? fex1 : ex1);
              fparam.getLstOperands().set(2, ex2 == null ? fex2 : ex2);

              return fparam;
            case FunctionExprent.FUNCTION_CADD:
            case FunctionExprent.FUNCTION_COR:
              List<Exprent> operands = fparam.getLstOperands();
              for (int i = 0; i < operands.size(); i++) {
                Exprent newparam = new FunctionExprent(FunctionExprent.FUNCTION_BOOL_NOT, operands.get(i), operands.get(i).bytecode);

                Exprent retparam = propagateBoolNot(newparam);
                operands.set(i, retparam == null ? newparam : retparam);
              }
            case FunctionExprent.FUNCTION_EQ:
            case FunctionExprent.FUNCTION_NE:
              canSimplify = true;
            case FunctionExprent.FUNCTION_LT:
            case FunctionExprent.FUNCTION_GE:
            case FunctionExprent.FUNCTION_GT:
            case FunctionExprent.FUNCTION_LE:
              if (!canSimplify) {
                operands = fparam.getLstOperands();
                VarType left = operands.get(0).getExprType();
                VarType right = operands.get(1).getExprType();
                VarType commonSupertype = VarType.getCommonSupertype(left, right);
                if (commonSupertype != null) {
                  canSimplify = commonSupertype.type != CodeConstants.TYPE_FLOAT && commonSupertype.type != CodeConstants.TYPE_DOUBLE;
                }
              }
              if (canSimplify) {
                fparam.setFuncType(funcsnot[ftype - FunctionExprent.FUNCTION_EQ]);
                return fparam;
              }
          }
        }
      }
    }

    return null;
  }

  /**
   * Simplifies assignment exprents that can be represented as a compound assignment.
   * Example: "a = a + b" becomes "a += b"
   * Iterates recursively through every statement within the statement and all assignments possible.
   * See: <a href="https://docs.oracle.com/javase/specs/jls/se16/html/jls-15.html#jls-15.26.2">JLS-15.26.2</a>
   *
   * @param stat The provided statement
   */
  public static boolean updateAssignments(Statement stat) {
    boolean res = false;
    // Get all sequential objects if the statement doesn't have exprents
    List<Object> objects = new ArrayList<>(stat.getExprents() == null ? stat.getSequentialObjects() : stat.getExprents());

    for (Object obj : objects) {
      if (obj instanceof Statement) {
        // If the object is a statement, recurse
        res |= updateAssignments((Statement) obj);
      } else if (obj instanceof Exprent) {
        // If the statement is an exprent, start processing
        Exprent exprent = (Exprent) obj;

        if (exprent.type == Exprent.EXPRENT_ASSIGNMENT) {
          AssignmentExprent assignment = (AssignmentExprent) exprent;

          List<Exprent> params = exprent.getAllExprents();

          // Get params of the assignment exprent
          Exprent lhs = params.get(0);
          Exprent rhs = params.get(1);

          // We only want expressions that are standard assignments where the left hand side is a variable and the right hand side is a function.
          if (assignment.getCondType() == -1 && lhs.type == Exprent.EXPRENT_VAR && rhs.type == Exprent.EXPRENT_FUNCTION) {
            VarExprent lhsVar = (VarExprent) lhs;
            FunctionExprent rhsFunc = (FunctionExprent) rhs;

            List<Exprent> funcParams = rhsFunc.getAllExprents();

            // Make sure that the function is a mathematical or bit shift function
            if (rhsFunc.getFuncType() <= FunctionExprent.FUNCTION_USHR && funcParams.get(0).type == Exprent.EXPRENT_VAR) {
              // Get the left hand side of the function
              VarExprent lhsVarFunc = (VarExprent) funcParams.get(0);

              // Check if the left hand side of the assignment and the left hand side of the function are the same variable
              // TODO: maybe we should be checking for var version equality too?
              if (lhsVar.getIndex() == lhsVarFunc.getIndex()) {
                // If all the checks succeed, set the assignment to be a compound assignment and set the right hand side to be the 2nd part of the function
                assignment.setCondType(rhsFunc.getFuncType());
                assignment.setRight(funcParams.get(1));
                // TODO: doesn't hit all instances, see ClientWorld

                res = true;
              }
            }
          }
        }
      }
    }

    return res;
  }
}
