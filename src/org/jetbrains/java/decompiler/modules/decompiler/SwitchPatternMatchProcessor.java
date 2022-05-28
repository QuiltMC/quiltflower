package org.jetbrains.java.decompiler.modules.decompiler;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.modules.decompiler.exps.*;
import org.jetbrains.java.decompiler.modules.decompiler.stats.*;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.util.Pair;

import java.util.*;
import java.util.stream.Collectors;

public final class SwitchPatternMatchProcessor {
  public static boolean processPatternMatching(Statement root) {
    boolean ret = processPatternMatchingRec(root, root);

    if (ret) {
      SequenceHelper.condenseSequences(root);
    }

    return ret;
  }

  private static boolean processPatternMatchingRec(Statement stat, Statement root) {
    boolean ret = false;
    for (Statement st : new ArrayList<>(stat.getStats())) {
      ret |= processPatternMatchingRec(st, root);
    }

    if (stat instanceof SwitchStatement) {
      ret |= processStatement((SwitchStatement) stat, root);
    }

    return ret;
  }

  private static boolean processStatement(SwitchStatement stat, Statement root) {
    if (stat.isPhantom()) {
      return false;
    }

    SwitchHeadExprent head = (SwitchHeadExprent)stat.getHeadexprent();

    boolean switchPatternMatch = isSwitchPatternMatch(head);

    if (!switchPatternMatch) {
      return false;
    }

    // Found switch pattern match, start applying basic transformations
    // replace `SwitchBootstraps.typeSwitch<...>(o, idx)` with `o`
    // if `idx` is used in one place, there's no guards and we can quickly remove it
    // otherwise, we need to look at every usage and eliminate guards
    InvocationExprent value = (InvocationExprent) head.getValue();
    List<Exprent> origParams = value.getLstParameters();
    boolean guarded = true;
    List<Pair<Statement, Exprent>> references = new ArrayList<>();
    if (origParams.get(1) instanceof VarExprent) {
      VarExprent var = (VarExprent) origParams.get(1);
      SwitchHelper.findExprents(root, Exprent.class, var::isVarReferenced, false, (st, expr) -> references.add(Pair.of(st, expr)));
      // If we have one reference...
      if (references.size() == 1) {
        // ...and its just assignment...
        Pair<Statement, Exprent> ref = references.get(0);
        if (ref.b instanceof AssignmentExprent) {
          // ...remove the variable
          ref.a.getExprents().remove(ref.b);
          guarded = false;
        }
      }
    }

    Map<List<Exprent>, Exprent> guards = new HashMap<>(0);
    if (guarded) {
      guards = new HashMap<>(references.size());
      // a guard takes the form of exactly
      // `if (!guardCond) { idx = __thisIdx + 1; break; }`
      // at the start of that branch
      // alternatively, it can be inverted as `if (guardCond) { /* regular case code... */ break; } idx = __thisIdx + 1;`
      // remove the initial assignment to 0
      Pair<Statement, Exprent> refA = references.get(0);
      if (refA.b instanceof AssignmentExprent && ((AssignmentExprent) refA.b).getRight() instanceof ConstExprent) {
        ConstExprent constExprent = (ConstExprent) ((AssignmentExprent) refA.b).getRight();
        if (constExprent.getConstType().typeFamily == CodeConstants.TYPE_FAMILY_INTEGER && constExprent.getIntValue() == 0) {
          refA.a.getExprents().remove(refA.b);
          references.remove(0);
        }
      }
      // look at every assignment of `idx`
      for (Pair<Statement, Exprent> reference : references) {
        if (reference.b instanceof AssignmentExprent) {
          Statement assignStat = reference.a;
          // check if the assignment follows the guard layout
          Statement parent = assignStat.getParent();
          // sometimes the assignment is after the `if` and it's condition is inverted [TestSwitchPatternMatchingInstanceof]
          boolean invert = true;
          if (parent instanceof SequenceStatement && parent.getStats().size() == 2 && parent.getStats().get(1) == assignStat) {
            parent = parent.getStats().get(0);
            invert = false;
          }
          // the assignment should be alone in a basic block, contained in an `if`, contained in a sequence, within the `switch`
          if (assignStat instanceof BasicBlockStatement
              && assignStat.getExprents().size() == 1
              && parent instanceof IfStatement
              && parent.getParent() instanceof SequenceStatement
              && parent.getParent().getParent() == stat) {
            Statement next = assignStat.getSuccessorEdges(StatEdge.TYPE_CONTINUE).get(0).getDestination();
            if (next == stat.getParent()) {
              IfStatement guardIf = (IfStatement) parent;
              // the condition of the `if` is the guard condition, usually inverted
              Exprent guardExprent = guardIf.getHeadexprent().getCondition();
              // find which case branch we're in (to assign the guard to)
              List<Statement> caseStatements = stat.getCaseStatements();
              for (int i = 0; i < caseStatements.size(); i++) {
                if (caseStatements.get(i).containsStatement(reference.a)) {
                  // the assignment of the pattern variable may be inside the `if`, take it out and add it to the next statement
                  List<Exprent> castExprent = Collections.singletonList(guardIf.getStats().get(0).getExprents().get(0));
                  if (invert) {
                    // normally the guard condition is inverted, re-invert it here
                    guardExprent = new FunctionExprent(FunctionExprent.FunctionType.BOOL_NOT, guardExprent, guardExprent.bytecode);
                  } else {
                    // if the index assignment is outside of the `if`, the contents of the `if` *is* the branch and should be added to next statement
                    castExprent = parent.getStats().stream().flatMap(x -> x.getExprents().stream()).collect(Collectors.toList());
                    assignStat.replaceWithEmpty(); // normally removed in guardIf.replaceWithEmpty()
                  }
                  guards.put(stat.getCaseValues().get(i), guardExprent);
                  // eliminate the guard `if`, alongside the assignment if not inverted
                  guardIf.replaceWithEmpty();
                  guardIf.getParent().getStats().remove(0);
                  Statement nextStat = guardIf.getParent().getStats().get(0);
                  // add the pattern variable assignment (or case code for inverted cases) to next statement
                  if (nextStat instanceof BasicBlockStatement) {
                    nextStat.getExprents().addAll(0, castExprent);
                  } else {
                    nextStat.getFirst().getExprents().addAll(0, castExprent);
                  }
                  break;
                }
              }
            }
          }
        }
      }
    }

    for (int i = 0; i < stat.getCaseStatements().size(); i++) {
      Statement caseStat = stat.getCaseStatements().get(i);

      List<Exprent> allCases = stat.getCaseValues().get(i);
      Exprent caseExpr = allCases.get(0);

      // null expression = default case, can't be shared with patterns
      if (caseExpr == null) {
        continue;
      }

      if (guards.containsKey(allCases)) {
        // add the guard to the same index as this case, padding the list with nulls as necessary
        while(stat.getCaseGuards().size() <= i)
          stat.getCaseGuards().add(null);
        stat.getCaseGuards().set(i, guards.get(allCases));
      }
      if (caseExpr instanceof ConstExprent) {
        int caseValue = ((ConstExprent)caseExpr).getIntValue();

        // -1 always means null
        if (caseValue == -1) {
          allCases.remove(caseExpr);
          ConstExprent nullConst = new ConstExprent(VarType.VARTYPE_NULL, null, null);
          // null can be shared with a pattern or default; put it at the end, but before default, to make sure it doesn't get
          // absorbed by the default or overwritten by a pattern
          if (allCases.contains(null)) {
            allCases.add(allCases.indexOf(null), nullConst);
          } else {
            allCases.add(nullConst);
          }
        }
      }

      // find the pattern variable assignment
      if (caseStat instanceof SequenceStatement) {
        Statement oldStat = caseStat;
        caseStat = caseStat.getStats().get(0);
        // we can end up with a SequenceStatement with 1 statement from guard `if` elimination, eliminate the sequence entirely
        if (oldStat.getStats().size() == 1) {
          oldStat.replaceWith(caseStat);
        }
      }
      // the pattern assignment might be absorbed by another other statement (like a DoStat or IfStat) as its "first"
      if (!(caseStat instanceof BasicBlockStatement)) {
        caseStat = caseStat.getFirst();
      }
      // make instanceof from assignment
      BasicBlockStatement caseStatBlock = (BasicBlockStatement)caseStat;
      if (caseStatBlock.getExprents().size() >= 1) {
        Exprent expr = caseStatBlock.getExprents().get(0);
        if (expr instanceof AssignmentExprent) {
          AssignmentExprent assign = (AssignmentExprent)expr;

          if (assign.getLeft() instanceof VarExprent) {
            VarExprent var = (VarExprent)assign.getLeft();

            if (assign.getRight() instanceof FunctionExprent && ((FunctionExprent)assign.getRight()).getFuncType() == FunctionExprent.FunctionType.CAST) {
              FunctionExprent cast = (FunctionExprent)assign.getRight();

              List<Exprent> operands = new ArrayList<>();
              operands.add(cast.getLstOperands().get(0)); // checking var
              operands.add(cast.getLstOperands().get(1)); // type
              operands.add(var); // pattern match var

              FunctionExprent func = new FunctionExprent(FunctionExprent.FunctionType.INSTANCEOF, operands, null);

              caseStatBlock.getExprents().remove(0);

              // TODO: ssau representation
              // any shared nulls will be at the end, and patterns & defaults can't be shared, so its safe to overwrite whatever's here
              allCases.set(0, func);
            }
          }
        }
      }
    }

    List<StatEdge> sucs = stat.getSuccessorEdges(StatEdge.TYPE_REGULAR);

    if (!sucs.isEmpty()) {

      Statement suc = sucs.get(0).getDestination();
      if (!(suc instanceof BasicBlockStatement)) { // make basic block if it isn't found
        Statement oldSuc = suc;

        suc = BasicBlockStatement.create();
        SequenceStatement seq = new SequenceStatement(stat, suc);

        seq.setParent(stat.getParent());

        stat.replaceWith(seq);

        seq.setAllParent();

        // Replace successors with the new basic block
        for (Statement st : stat.getCaseStatements()) {
          for (StatEdge edge : st.getAllSuccessorEdges()) {
            if (edge.getDestination() == oldSuc) {
              st.removeSuccessor(edge);

              st.addSuccessor(new StatEdge(edge.getType(), st, suc, seq));
            }
          }
        }

        // Control flow from new basic block to the next one
        suc.addSuccessor(new StatEdge(StatEdge.TYPE_REGULAR, suc, oldSuc, seq));
      }

      stat.setPhantom(true);
      suc.getExprents().add(0, new SwitchExprent(stat, VarType.VARTYPE_INT, false, true));
    }

    head.setValue(origParams.get(0));

    if (guarded && stat.getParent() instanceof DoStatement) {
      // remove the enclosing while(true) loop of a guarded switch
      stat.getParent().replaceWith(stat);
      // and remove any invalid `continue` edges to the switch
      for (StatEdge edge : stat.getPredecessorEdges(StatEdge.TYPE_CONTINUE)) {
        stat.removePredecessor(edge);
        edge.getSource().removeSuccessor(edge);
      }
    }

    return false;
  }

  private static boolean isSwitchPatternMatch(SwitchHeadExprent head) {
    Exprent value = head.getValue();

    if (value instanceof InvocationExprent) {
      InvocationExprent invoc = (InvocationExprent)value;

      return invoc.getInvocationType() == InvocationExprent.InvocationType.DYNAMIC && invoc.getName().equals("typeSwitch");
    }

    return false;
  }
}
