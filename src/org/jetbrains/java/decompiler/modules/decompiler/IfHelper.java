// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.modules.decompiler;

import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.FunctionExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.IfExprent;
import org.jetbrains.java.decompiler.modules.decompiler.stats.IfStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.SequenceStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;

import java.util.*;

public final class IfHelper {
  public static boolean mergeAllIfs(RootStatement root) {
    boolean res = mergeAllIfsRec(root, new HashSet<>());
    if (res) {
      SequenceHelper.condenseSequences(root);
    }
    return res;
  }

  private static boolean mergeAllIfsRec(Statement stat, Set<? super Integer> setReorderedIfs) {
    boolean res = false;

    if (stat.getExprents() == null) {
      while (true) {
        boolean changed = false;

        for (Statement st : stat.getStats()) {
          res |= mergeAllIfsRec(st, setReorderedIfs);

          // collapse composed if's
          if (mergeIfs(st, setReorderedIfs)) {
            changed = true;
            break;
          }
        }

        res |= changed;

        if (!changed) {
          break;
        }
      }
    }

    return res;
  }

  /*
   * convert
   * l: {
   *     if (condA) {
   *         break l;
   *     } else {
   *         ...
   *     }
   * }
   *
   * into
   *
   * if (!condA) {
   *     ...
   * }
   *
   * and
   *
   * l: {
   *     if (condA) {
   *         ...
   *     } else {
   *         break l;
   *     }
   * }
   *
   * into
   *
   * if (condA) {
   *     ...
   * }
   */
  public static boolean relaxIfs(Statement statement) {
    if (statement.type != Statement.TYPE_SEQUENCE) {
      return false;
    }

    List<Statement> stats = statement.getStats();

    boolean changed = false;

    for (int i = 1; i < stats.size(); i++) {
      Statement stat = stats.get(i - 1);
      Statement nextStat = stats.get(i);

      if (stat.type == Statement.TYPE_IF) {
        IfStatement ifstat = (IfStatement)stat;
        changed |= fixIf(ifstat, nextStat);
      }
    }

    return changed;
  }

  public static boolean fixIf(IfStatement ifstatement, Statement nextStat) {
    boolean changed = false;
    // clean up empty if block
    if (ifstatement.getSuccessorEdges(Statement.STATEDGE_DIRECT_ALL).size() != 1) {
      // this feels illegal
      return false;
    }
    Statement afterIf = ifstatement.getSuccessorEdges(Statement.STATEDGE_DIRECT_ALL).get(0).getDestination();

    o: if (ifstatement.getIfstat() != null &&
           ifstatement.getElseEdge() != null &&
           ifstatement.getIfstat().type == Statement.TYPE_BASICBLOCK &&
           ifstatement.getIfstat().getExprents().isEmpty()) {
      final Statement ifstat = ifstatement.getIfstat();


      final List<StatEdge> successorEdges = ifstat.getSuccessorEdges(Statement.STATEDGE_DIRECT_ALL);
      if (successorEdges.size() != 1) {
        // this feels illegal
        break o;
      }

      StatEdge successorEdge = successorEdges.get(0);
      if(successorEdge.getDestination() != afterIf) {
        break o;
      }

      StatEdge ifEdge = ifstatement.getIfEdge();
      ifstatement.getFirst().removeSuccessor(ifEdge);

      ifstatement.setIfstat(null);

      ifstat.removeSuccessor(successorEdge);
      successorEdge.setSource(ifstatement.getFirst());
      ifstatement.getFirst().addSuccessor(successorEdge);
      ifstatement.setIfEdge(successorEdge);

      ifstatement.getStats().removeWithKey(ifstat.id);

      changed = true;
    }

    // can't make both null
    o: if (ifstatement.getIfstat() != null &&
           ifstatement.getElsestat() != null &&
           ifstatement.getElsestat().type == Statement.TYPE_BASICBLOCK &&
           ifstatement.getElsestat().getExprents().isEmpty()) {
      final Statement elsestat = ifstatement.getElsestat();


      final List<StatEdge> successorEdges = elsestat.getSuccessorEdges(Statement.STATEDGE_DIRECT_ALL);
      if (successorEdges.size() != 1) {
        break o;
      }

      StatEdge successorEdge = successorEdges.get(0);
      if(successorEdge.getDestination() != afterIf) {
        break o;
      }

      StatEdge elseEdge = ifstatement.getElseEdge();
      ifstatement.getFirst().removeSuccessor(elseEdge);

      ifstatement.setElsestat(null);

      elsestat.removeSuccessor(successorEdge);
      successorEdge.setSource(ifstatement.getFirst());
      ifstatement.getFirst().addSuccessor(successorEdge);
      ifstatement.setElseEdge(successorEdge);

      ifstatement.getStats().removeWithKey(elsestat.id);

      changed = true;
    }


    if (ifstatement.getIfstat() == null && ifstatement.getElsestat() != null) {
      // swap
      StatEdge oldIfEdge = ifstatement.getIfEdge();
      StatEdge oldElseEdge = ifstatement.getElseEdge();
      ifstatement.setIfEdge(oldElseEdge);
      ifstatement.setElseEdge(null);

      oldIfEdge.getSource().removeSuccessor(oldIfEdge);

      ifstatement.getHeadexprent().negateIf();

      ifstatement.setIfstat(ifstatement.getElsestat());
      ifstatement.setElsestat(null);
      ifstatement.iftype = IfStatement.IFTYPE_IF;

      changed = true;
    }

    return changed;
  }

  public static boolean mergeIfs(Statement statement, Set<? super Integer> setReorderedIfs) {
    if (statement.type != Statement.TYPE_IF && statement.type != Statement.TYPE_SEQUENCE) {
      return false;
    }

    boolean res = false;

    while (true) {
      boolean updated = false;

      List<Statement> lst = new ArrayList<>();
      if (statement.type == Statement.TYPE_IF) {
        lst.add(statement);
      } else {
        lst.addAll(statement.getStats());
      }

      boolean stsingle = (lst.size() == 1);

      for (Statement stat : lst) {
        if (stat.type == Statement.TYPE_IF) {
          IfNode rtnode = buildGraph((IfStatement)stat, stsingle);

          if (rtnode == null) {
            continue;
          }

          if (collapseIfIf(rtnode)) {
            updated = true;
            break;
          }

          if (!setReorderedIfs.contains(stat.id)) {
            if (collapseIfElse(rtnode)) {
              updated = true;
              break;
            }

            if (collapseElse(rtnode)) {
              updated = true;
              break;
            }
          }

          if (reorderIf((IfStatement)stat)) {
            updated = true;
            setReorderedIfs.add(stat.id);
            break;
          }
        }
      }

      if (!updated) {
        break;
      }

      res = true;
    }

    return res;
  }

  private static boolean collapseIfIf(IfNode rtnode) {
    if (rtnode.edgetypes.get(0) == 0) {
      IfNode ifbranch = rtnode.succs.get(0);
      if (ifbranch.succs.size() == 2 && rtnode.succs.size() >= 2) {

        // if-if branch
        if (ifbranch.succs.get(1).value == rtnode.succs.get(1).value) {

          IfStatement ifparent = (IfStatement)rtnode.value;
          IfStatement ifchild = (IfStatement)ifbranch.value;
          Statement ifinner = ifbranch.succs.get(0).value;

          if (ifchild.getFirst().getExprents().isEmpty()) {

            ifparent.getFirst().removeSuccessor(ifparent.getIfEdge());
            ifchild.removeSuccessor(ifchild.getAllSuccessorEdges().get(0));
            ifparent.getStats().removeWithKey(ifchild.id);

            if (ifbranch.edgetypes.get(0) == 1) { // target null

              ifparent.setIfstat(null);

              StatEdge ifedge = ifchild.getIfEdge();

              ifchild.getFirst().removeSuccessor(ifedge);
              ifedge.setSource(ifparent.getFirst());

              if (ifedge.closure == ifchild) {
                ifedge.closure = null;
              }
              ifparent.getFirst().addSuccessor(ifedge);

              ifparent.setIfEdge(ifedge);
            }
            else {
              ifchild.getFirst().removeSuccessor(ifchild.getIfEdge());

              StatEdge ifedge = new StatEdge(StatEdge.TYPE_REGULAR, ifparent.getFirst(), ifinner);
              ifparent.getFirst().addSuccessor(ifedge);
              ifparent.setIfEdge(ifedge);
              ifparent.setIfstat(ifinner);

              ifparent.getStats().addWithKey(ifinner, ifinner.id);
              ifinner.setParent(ifparent);

              if (!ifinner.getAllSuccessorEdges().isEmpty()) {
                StatEdge edge = ifinner.getAllSuccessorEdges().get(0);
                if (edge.closure == ifchild) {
                  edge.closure = null;
                }
              }
            }

            // merge if conditions
            IfExprent statexpr = ifparent.getHeadexprent();

            List<Exprent> lstOperands = new ArrayList<>();
            lstOperands.add(statexpr.getCondition());
            lstOperands.add(ifchild.getHeadexprent().getCondition());

            statexpr.setCondition(new FunctionExprent(FunctionExprent.FUNCTION_CADD, lstOperands, null));
            statexpr.addBytecodeOffsets(ifchild.getHeadexprent().bytecode);

            return true;
          }
        }
      }
    }

    return false;
  }

  private static boolean collapseIfElse(IfNode rtnode) {
    if (rtnode.edgetypes.get(0) == 0) {
      IfNode ifbranch = rtnode.succs.get(0);
      if (ifbranch.succs.size() == 2 && rtnode.succs.size() >= 2) {
        // if-else branch
        if (ifbranch.succs.get(0).value == rtnode.succs.get(1).value) {

          IfStatement ifparent = (IfStatement)rtnode.value;
          IfStatement ifchild = (IfStatement)ifbranch.value;

          if (ifchild.getFirst().getExprents().isEmpty()) {

            ifparent.getFirst().removeSuccessor(ifparent.getIfEdge());
            ifchild.getFirst().removeSuccessor(ifchild.getIfEdge());
            ifparent.getStats().removeWithKey(ifchild.id);

            if (ifbranch.edgetypes.get(1) == 1 &&
                ifbranch.edgetypes.get(0) == 1) { // target null

              ifparent.setIfstat(null);

              StatEdge ifedge = ifchild.getAllSuccessorEdges().get(0);

              ifchild.removeSuccessor(ifedge);
              ifedge.setSource(ifparent.getFirst());
              ifparent.getFirst().addSuccessor(ifedge);

              ifparent.setIfEdge(ifedge);
            }
            else {
              throw new IllegalStateException("inconsistent if structure in statement " + ifbranch.value + "!");
            }

            // merge if conditions
            IfExprent statexpr = ifparent.getHeadexprent();

            List<Exprent> lstOperands = new ArrayList<>();
            lstOperands.add(statexpr.getCondition());
            lstOperands.add(new FunctionExprent(FunctionExprent.FUNCTION_BOOL_NOT, ifchild.getHeadexprent().getCondition(), null));
            statexpr.setCondition(new FunctionExprent(FunctionExprent.FUNCTION_CADD, lstOperands, null));
            statexpr.addBytecodeOffsets(ifchild.getHeadexprent().bytecode);

            return true;
          }
        }
      }
    }

    return false;
  }

  private static boolean collapseElse(IfNode rtnode) {
    if (rtnode.edgetypes.size() >= 2 && rtnode.edgetypes.get(1) == 0) {
      IfNode elsebranch = rtnode.succs.get(1);
      if (elsebranch.succs.size() == 2) {

        // else-if or else-else branch
        int path = elsebranch.succs.get(1).value == rtnode.succs.get(0).value ? 2 :
                   (elsebranch.succs.get(0).value == rtnode.succs.get(0).value ? 1 : 0);

        if (path > 0) {

          IfStatement firstif = (IfStatement)rtnode.value;
          IfStatement secondif = (IfStatement)elsebranch.value;
          Statement parent = firstif.getParent();

          if (secondif.getFirst().getExprents().isEmpty()) {

            firstif.getFirst().removeSuccessor(firstif.getIfEdge());

            // remove first if
            firstif.removeAllSuccessors(secondif);

            for (StatEdge edge : firstif.getAllPredecessorEdges()) {
              if (!firstif.containsStatementStrict(edge.getSource())) {
                firstif.removePredecessor(edge);
                edge.getSource().changeEdgeNode(Statement.DIRECTION_FORWARD, edge, secondif);
                secondif.addPredecessor(edge);
              }
            }

            parent.getStats().removeWithKey(firstif.id);
            if (parent.getFirst() == firstif) {
              parent.setFirst(secondif);
            }

            // merge if conditions
            IfExprent statexpr = secondif.getHeadexprent();

            List<Exprent> lstOperands = new ArrayList<>();
            lstOperands.add(firstif.getHeadexprent().getCondition());

            if (path == 2) {
              lstOperands.set(0, new FunctionExprent(FunctionExprent.FUNCTION_BOOL_NOT, lstOperands.get(0), null));
            }

            lstOperands.add(statexpr.getCondition());

            statexpr
              .setCondition(new FunctionExprent(path == 1 ? FunctionExprent.FUNCTION_COR : FunctionExprent.FUNCTION_CADD, lstOperands, null));

            if (secondif.getFirst().getExprents().isEmpty() &&
                !firstif.getFirst().getExprents().isEmpty()) {

              secondif.replaceStatement(secondif.getFirst(), firstif.getFirst());
            }

            return true;
          }
        }
      }
      else if (elsebranch.succs.size() == 1) {
        if (elsebranch.succs.get(0).value == rtnode.succs.get(0).value) {
          IfStatement firstif = (IfStatement)rtnode.value;
          Statement second = elsebranch.value;

          firstif.removeAllSuccessors(second);

          for (StatEdge edge : second.getAllSuccessorEdges()) {
            second.removeSuccessor(edge);
            edge.setSource(firstif);
            firstif.addSuccessor(edge);
          }

          StatEdge ifedge = firstif.getIfEdge();
          firstif.getFirst().removeSuccessor(ifedge);

          second.addSuccessor(new StatEdge(ifedge.getType(), second, ifedge.getDestination(), ifedge.closure));

          StatEdge newifedge = new StatEdge(StatEdge.TYPE_REGULAR, firstif.getFirst(), second);
          firstif.getFirst().addSuccessor(newifedge);
          firstif.setIfstat(second);

          firstif.getStats().addWithKey(second, second.id);
          second.setParent(firstif);

          firstif.getParent().getStats().removeWithKey(second.id);

          // negate the if condition
          IfExprent statexpr = firstif.getHeadexprent();
          statexpr
            .setCondition(new FunctionExprent(FunctionExprent.FUNCTION_BOOL_NOT, statexpr.getCondition(), null));

          return true;
        }
      }
    }

    // Convert
    //
    // if (condA) {
    //   if (condB) {
    //     X
    //   } else {
    //     Y
    //   }
    //   goto end
    // }
    // Z
    // end
    //
    // To
    //
    // if (!condA) {
    //   Z
    // }
    // else {
    //   if (condB) {
    //     X
    //   } else {
    //     Y
    //   }
    // }
    //
    // (Which is rendered as if/elseif/else)

    if (rtnode.edgetypes.get(0) == 0) {
      IfStatement outerIf = (IfStatement) rtnode.value;
      if (outerIf.getParent().type == Statement.TYPE_SEQUENCE) {
        SequenceStatement parent = (SequenceStatement) outerIf.getParent();
        Statement nestedStat = rtnode.succs.get(0).value;

        // check that statements Y and Z (see above) jump to end
        boolean ifdirect = hasDirectEndEdge(nestedStat, parent);
        boolean elsedirect = hasDirectEndEdge(parent.getStats().getLast(), parent);
        if (ifdirect && elsedirect) {
          // check there is a nested if statement that doesn't have any exprents before it, and that statement X jumps to end
          IfStatement nestedIf = nestedStat.type == Statement.TYPE_IF ? (IfStatement) nestedStat
            : nestedStat.type == Statement.TYPE_SEQUENCE && nestedStat.getFirst().type == Statement.TYPE_IF ? (IfStatement) nestedStat.getFirst() : null;
          if (nestedIf != null && nestedIf.getFirst().getExprents().isEmpty() && nestedIf.getIfstat() != null && hasDirectEndEdge(nestedIf.getIfstat(), parent)) {
            // check that statement Z is not an if statement (without exprents before it)
            List<StatEdge> successors = outerIf.getAllSuccessorEdges();
            Statement nextStat = !successors.isEmpty() && successors.get(0).getType() == StatEdge.TYPE_REGULAR ? successors.get(0).getDestination() : null;
            IfStatement nextIfStat = nextStat == null ? null : nextStat.type == Statement.TYPE_IF ? (IfStatement) nextStat
              : nextStat.type == Statement.TYPE_SEQUENCE && nextStat.getFirst().type == Statement.TYPE_IF ? (IfStatement) nextStat.getFirst() : null;
            if (nextStat != null && (nextIfStat == null || !nextIfStat.getFirst().getExprents().isEmpty())) {
              // negate the condition and swap the branches
              IfExprent conditionExprent = outerIf.getHeadexprent();
              conditionExprent.setCondition(new FunctionExprent(FunctionExprent.FUNCTION_BOOL_NOT, conditionExprent.getCondition(), null));
              swapBranches(outerIf, false, parent);
            }
          }
        }
      }
    }

    return false;
  }

  private static IfNode buildGraph(IfStatement stat, boolean stsingle) {
    if (stat.iftype == IfStatement.IFTYPE_IFELSE) {
      return null;
    }

    IfNode res = new IfNode(stat);

    // if branch
    Statement ifchild = stat.getIfstat();
    if (ifchild == null) {
      StatEdge edge = stat.getIfEdge();
      res.addChild(new IfNode(edge.getDestination()), 1);
    }
    else {
      IfNode ifnode = new IfNode(ifchild);
      res.addChild(ifnode, 0);
      if (ifchild.type == Statement.TYPE_IF && ((IfStatement)ifchild).iftype == IfStatement.IFTYPE_IF) {
        IfStatement stat2 = (IfStatement)ifchild;
        Statement ifchild2 = stat2.getIfstat();
        if (ifchild2 == null) {
          StatEdge edge = stat2.getIfEdge();
          ifnode.addChild(new IfNode(edge.getDestination()), 1);
        }
        else {
          ifnode.addChild(new IfNode(ifchild2), 0);
        }
      }

      if (!ifchild.getAllSuccessorEdges().isEmpty()) {
        ifnode.addChild(new IfNode(ifchild.getAllSuccessorEdges().get(0).getDestination()), 1);
      }
    }

    // else branch
    List<StatEdge> successorEdges = stat.getAllSuccessorEdges();
    if (successorEdges.isEmpty()) {
      return res;
    }
    StatEdge edge = successorEdges.get(0);
    Statement elsechild = edge.getDestination();
    IfNode elsenode = new IfNode(elsechild);

    if (stsingle || edge.getType() != StatEdge.TYPE_REGULAR) {
      res.addChild(elsenode, 1);
    }
    else {
      res.addChild(elsenode, 0);
      if (elsechild.type == Statement.TYPE_IF && ((IfStatement)elsechild).iftype == IfStatement.IFTYPE_IF) {
        IfStatement stat2 = (IfStatement)elsechild;
        Statement ifchild2 = stat2.getIfstat();
        if (ifchild2 == null) {
          elsenode.addChild(new IfNode(stat2.getIfEdge().getDestination()), 1);
        }
        else {
          elsenode.addChild(new IfNode(ifchild2), 0);
        }
      }

      if (!elsechild.getAllSuccessorEdges().isEmpty()) {
        elsenode.addChild(new IfNode(elsechild.getAllSuccessorEdges().get(0).getDestination()), 1);
      }
    }

    return res;
  }

  // FIXME: rewrite the entire method!!! keep in mind finally exits!!
  private static boolean reorderIf(IfStatement ifstat) {
    if (ifstat.iftype == IfStatement.IFTYPE_IFELSE) {
      return false;
    }

    // Cannot reorder pattern matches, causes semantic issues!
    // TODO: proper pattern match reorder analysis
    if (ifstat.isPatternMatched()) {
      return false;
    }

    boolean ifdirect, elsedirect;
    boolean noifstat = false, noelsestat;
    boolean ifdirectpath = false, elsedirectpath = false;

    Statement parent = ifstat.getParent();
    Statement from = parent.type == Statement.TYPE_SEQUENCE ? parent : ifstat;

    Statement next = getNextStatement(from);

    if (ifstat.getIfstat() == null) {
      noifstat = true;

      ifdirect = ifstat.getIfEdge().getType() == StatEdge.TYPE_FINALLYEXIT ||
                 MergeHelper.isDirectPath(from, ifstat.getIfEdge().getDestination());
    }
    else {
      List<StatEdge> lstSuccs = ifstat.getIfstat().getAllSuccessorEdges();
      ifdirect = !lstSuccs.isEmpty() && lstSuccs.get(0).getType() == StatEdge.TYPE_FINALLYEXIT ||
                 hasDirectEndEdge(ifstat.getIfstat(), from);
    }

    Statement last = parent.type == Statement.TYPE_SEQUENCE ? parent.getStats().getLast() : ifstat;
    noelsestat = (last == ifstat);

    elsedirect = !last.getAllSuccessorEdges().isEmpty() && last.getAllSuccessorEdges().get(0).getType() == StatEdge.TYPE_FINALLYEXIT ||
                 hasDirectEndEdge(last, from);

    List<StatEdge> successors = ifstat.getAllSuccessorEdges();

    if (successors.isEmpty()) {
      // Can't have no successors- something went wrong horribly somewhere!
      throw new IllegalStateException("If statement " + ifstat + " has no successors!");
    }

    if (!noelsestat && existsPath(ifstat, successors.get(0).getDestination())) {
      return false;
    }

    if (!ifdirect && !noifstat) {
      ifdirectpath = existsPath(ifstat, next);
    }

    if (!elsedirect && !noelsestat) {
      SequenceStatement sequence = (SequenceStatement)parent;

      for (int i = sequence.getStats().size() - 1; i >= 0; i--) {
        Statement sttemp = sequence.getStats().get(i);
        if (sttemp == ifstat) {
          break;
        }
        else if (existsPath(sttemp, next)) {
          elsedirectpath = true;
          break;
        }
      }
    }

    if ((ifdirect || ifdirectpath) && (elsedirect || elsedirectpath) && !noifstat && !noelsestat) {  // if - then - else

      SequenceStatement sequence = (SequenceStatement)parent;

      // build and cut the new else statement
      List<Statement> lst = new ArrayList<>();
      for (int i = sequence.getStats().size() - 1; i >= 0; i--) {
        Statement sttemp = sequence.getStats().get(i);
        if (sttemp == ifstat) {
          break;
        }
        else {
          lst.add(0, sttemp);
        }
      }

      Statement stelse;
      if (lst.size() == 1) {
        stelse = lst.get(0);
      }
      else {
        stelse = new SequenceStatement(lst);
        stelse.setAllParent();
      }

      ifstat.removeSuccessor(ifstat.getAllSuccessorEdges().get(0));
      for (Statement st : lst) {
        sequence.getStats().removeWithKey(st.id);
      }

      StatEdge elseedge = new StatEdge(StatEdge.TYPE_REGULAR, ifstat.getFirst(), stelse);
      ifstat.getFirst().addSuccessor(elseedge);
      ifstat.setElsestat(stelse);
      ifstat.setElseEdge(elseedge);

      ifstat.getStats().addWithKey(stelse, stelse.id);
      stelse.setParent(ifstat);

      //			if(next.type != Statement.TYPE_DUMMYEXIT && (ifdirect || elsedirect)) {
      //	 			StatEdge breakedge = new StatEdge(StatEdge.TYPE_BREAK, ifstat, next);
      //				sequence.addLabeledEdge(breakedge);
      //				ifstat.addSuccessor(breakedge);
      //			}

      ifstat.iftype = IfStatement.IFTYPE_IFELSE;
    }
    else if (ifdirect && (!elsedirect || (noifstat && !noelsestat)) && !ifstat.getAllSuccessorEdges().isEmpty()) {  // if - then
      // negate the if condition
      IfExprent statexpr = ifstat.getHeadexprent();
      statexpr.setCondition(new FunctionExprent(FunctionExprent.FUNCTION_BOOL_NOT, statexpr.getCondition(), null));

      if (noelsestat) {
        StatEdge ifedge = ifstat.getIfEdge();
        StatEdge elseedge = ifstat.getAllSuccessorEdges().get(0);

        if (noifstat) {
          ifstat.getFirst().removeSuccessor(ifedge);
          ifstat.removeSuccessor(elseedge);

          ifedge.setSource(ifstat);
          elseedge.setSource(ifstat.getFirst());

          ifstat.addSuccessor(ifedge);
          ifstat.getFirst().addSuccessor(elseedge);

          ifstat.setIfEdge(elseedge);
        }
        else {
          Statement ifbranch = ifstat.getIfstat();
          SequenceStatement newseq = new SequenceStatement(Arrays.asList(ifstat, ifbranch));

          ifstat.getFirst().removeSuccessor(ifedge);
          ifstat.getStats().removeWithKey(ifbranch.id);
          ifstat.setIfstat(null);

          ifstat.removeSuccessor(elseedge);
          elseedge.setSource(ifstat.getFirst());
          ifstat.getFirst().addSuccessor(elseedge);

          ifstat.setIfEdge(elseedge);

          ifstat.getParent().replaceStatement(ifstat, newseq);
          newseq.setAllParent();

          ifstat.addSuccessor(new StatEdge(StatEdge.TYPE_REGULAR, ifstat, ifbranch));
        }
      }
      else {
        swapBranches(ifstat, noifstat, (SequenceStatement) parent);
      }
    }
    else {
      return false;
    }

    return true;
  }

  private static void swapBranches(IfStatement ifstat, boolean noifstat, SequenceStatement parent) {
    // build and cut the new else statement
    List<Statement> lst = new ArrayList<>();
    for (int i = parent.getStats().size() - 1; i >= 0; i--) {
      Statement sttemp = parent.getStats().get(i);
      if (sttemp == ifstat) {
        break;
      }
      else {
        lst.add(0, sttemp);
      }
    }

    Statement stelse;
    if (lst.size() == 1) {
      stelse = lst.get(0);
    }
    else {
      stelse = new SequenceStatement(lst);
      stelse.setAllParent();
    }

    ifstat.removeSuccessor(ifstat.getAllSuccessorEdges().get(0));
    for (Statement st : lst) {
      parent.getStats().removeWithKey(st.id);
    }

    if (noifstat) {
      StatEdge ifedge = ifstat.getIfEdge();

      ifstat.getFirst().removeSuccessor(ifedge);
      ifedge.setSource(ifstat);
      ifstat.addSuccessor(ifedge);
    }
    else {
      Statement ifbranch = ifstat.getIfstat();

      ifstat.getFirst().removeSuccessor(ifstat.getIfEdge());
      ifstat.getStats().removeWithKey(ifbranch.id);

      ifstat.addSuccessor(new StatEdge(StatEdge.TYPE_REGULAR, ifstat, ifbranch));

      parent.getStats().addWithKey(ifbranch, ifbranch.id);
      ifbranch.setParent(parent);
    }

    StatEdge newifedge = new StatEdge(StatEdge.TYPE_REGULAR, ifstat.getFirst(), stelse);
    ifstat.getFirst().addSuccessor(newifedge);
    ifstat.setIfstat(stelse);
    ifstat.setIfEdge(newifedge);

    ifstat.getStats().addWithKey(stelse, stelse.id);
    stelse.setParent(ifstat);
  }

  private static boolean hasDirectEndEdge(Statement stat, Statement from) {

    for (StatEdge edge : stat.getAllSuccessorEdges()) {
      if (MergeHelper.isDirectPath(from, edge.getDestination())) {
        return true;
      }
    }

    if (stat.getExprents() == null) {
      switch (stat.type) {
        case Statement.TYPE_SEQUENCE:
          return hasDirectEndEdge(stat.getStats().getLast(), from);
        case Statement.TYPE_CATCHALL:
        case Statement.TYPE_TRYCATCH:
          for (Statement st : stat.getStats()) {
            if (hasDirectEndEdge(st, from)) {
              return true;
            }
          }
          break;
        case Statement.TYPE_IF:
          IfStatement ifstat = (IfStatement)stat;
          if (ifstat.iftype == IfStatement.IFTYPE_IFELSE) {
            return hasDirectEndEdge(ifstat.getIfstat(), from) ||
                   hasDirectEndEdge(ifstat.getElsestat(), from);
          }
          break;
        case Statement.TYPE_SYNCRONIZED:
          return hasDirectEndEdge(stat.getStats().get(1), from);
        case Statement.TYPE_SWITCH:
          for (Statement st : stat.getStats()) {
            if (hasDirectEndEdge(st, from)) {
              return true;
            }
          }
      }
    }

    return false;
  }

  private static Statement getNextStatement(Statement stat) {
    Statement parent = stat.getParent();
    switch (parent.type) {
      case Statement.TYPE_ROOT:
        return ((RootStatement)parent).getDummyExit();
      case Statement.TYPE_DO:
        return parent;
      case Statement.TYPE_SEQUENCE:
        SequenceStatement sequence = (SequenceStatement)parent;
        if (sequence.getStats().getLast() != stat) {
          for (int i = sequence.getStats().size() - 1; i >= 0; i--) {
            if (sequence.getStats().get(i) == stat) {
              return sequence.getStats().get(i + 1);
            }
          }
        }
    }

    return getNextStatement(parent);
  }

  private static boolean existsPath(Statement from, Statement to) {
    for (StatEdge edge : to.getAllPredecessorEdges()) {
      if (from.containsStatementStrict(edge.getSource())) {
        return true;
      }
    }

    return false;
  }

  // Models an if statement, child if statements and their successors.
  // Does not model grandchild if statements or successors of successors.
  // Does not model if statements with else branches.
  // Therefore the maximum amount of information that the root node can contain is:
  // root [IfStatement] {
  //   ifstat1 [IfStatement] {
  //      ifstat2 [Any Statement]
  //   }
  //   elsestat2 [Any Statement]
  // }
  // elsestat1 [If Statement] {
  //   ifstat3 [Any Statement]
  // }
  // elsestat3 [Any Statement]
  // Note that an "elsestat" in this context is simply the stat we jump to when the condition is false, even if it's
  // reachable from the if body (i.e. there should be no "else" keyword in the source code).
  private static class IfNode {
    // The stat that this node refers to. Root node will be an if stat, child nodes can be any stat.
    public final Statement value;
    public final List<IfNode> succs = new ArrayList<>();
    // edge types, 0 for direct, 1 for indirect (e.g. continue, break, implicit)
    public final List<Integer> edgetypes = new ArrayList<>();

    IfNode(Statement value) {
      this.value = value;
    }

    public void addChild(IfNode child, int type) {
      succs.add(child);
      edgetypes.add(type);
    }
  }
}