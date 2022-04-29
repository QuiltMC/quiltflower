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
import java.util.List;

public final class NormalizeIfHeadHelper {

  public static boolean normalizeIfHeads(Statement statement, VarProcessor varProc) {
    boolean res = false;

    for (Statement stat : statement.getStats()) {
      if (normalizeIfHeads(stat, varProc)) {
        res = true;
      }
    }


    if (statement instanceof IfStatement) {
      res |= normalizeIfHeads((IfStatement) statement, varProc);
    }

    return res;
  }

  private static boolean normalizeIfHeads(IfStatement ifStatement, VarProcessor varProc) {
    boolean res = false;

    final IfExprent headexprent = ifStatement.getHeadexprent();
    Exprent condition = headexprent.getCondition();

    boolean isInverted = false;
    if (condition instanceof FunctionExprent) {
      final FunctionExprent fexp = (FunctionExprent) condition;
      if (fexp.getFuncType() == FunctionExprent.FUNCTION_BOOL_NOT) {
        isInverted = true;
        condition = fexp.getLstOperands().get(0);
      }
    }

    switch (condition.type) {
      case Exprent.EXPRENT_FUNCTION: {
        final FunctionExprent fexpr = (FunctionExprent) condition;
        switch (fexpr.getFuncType()) {
          case FunctionExprent.FUNCTION_EQ:
          case FunctionExprent.FUNCTION_NE: {
            final Exprent left = fexpr.getLstOperands().get(0);
            final Exprent right = fexpr.getLstOperands().get(1);

            if (fexpr.getFuncType() == FunctionExprent.FUNCTION_EQ) {
              isInverted = !isInverted;
            }

            if (right.type == Exprent.EXPRENT_CONST && ((ConstExprent) right).getValue() == Integer.valueOf(0)) {
              switch (left.type) {
                case Exprent.EXPRENT_FUNCTION: {
                  final FunctionExprent fleft = (FunctionExprent) left;

                  switch (fleft.getFuncType()) {
                    case FunctionExprent.FUNCTION_INSTANCEOF: {
                      if (!isInverted) {
                        headexprent.setCondition(fleft);
                        fleft.addBytecodeOffsets(fexpr.bytecode);
                      } else {
                        List<Exprent> arguments = new ArrayList<>();
                        arguments.add(fleft);
                        headexprent.setCondition(new FunctionExprent(FunctionExprent.FUNCTION_BOOL_NOT, arguments, fexpr.bytecode));
                      }
                      res = true;
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    return res;
  }
}
