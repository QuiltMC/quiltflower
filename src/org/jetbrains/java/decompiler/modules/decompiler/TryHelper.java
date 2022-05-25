package org.jetbrains.java.decompiler.modules.decompiler;

import org.jetbrains.java.decompiler.modules.decompiler.stats.CatchAllStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.CatchStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.util.DotExporter;
import org.jetbrains.java.decompiler.util.StartEndPair;

import java.util.*;

public class TryHelper {
  public static boolean enhanceTryStats(RootStatement root, StructClass cl) {
    boolean ret = makeTryWithResourceRec(cl, root);

    if (ret) {
      SequenceHelper.condenseSequences(root);

      if (!cl.getVersion().hasNewTryWithResources()) {
        if (collapseTryRec(root)) {
          SequenceHelper.condenseSequences(root);
        }
      }
    }

    if (cl.getVersion().hasNewTryWithResources()) {
      if (mergeTrys(root)) {
        SequenceHelper.condenseSequences(root);

        ret = true;
      }
    }

    return ret;
  }

  private static boolean makeTryWithResourceRec(StructClass cl, Statement stat) {
    if (cl.getVersion().hasNewTryWithResources()) {
      boolean ret = false;
      if (stat.type == Statement.TYPE_TRYCATCH) {
        if (TryWithResourcesProcessor.makeTryWithResourceJ11((CatchStatement) stat)) {
          ret = true;
        }
      }

      for (Statement st : new ArrayList<>(stat.getStats())) {
        if (makeTryWithResourceRec(cl, st)) {
          ret = true;
        }
      }

      return ret;
    } else {
      if (stat.type == Statement.TYPE_CATCHALL && ((CatchAllStatement) stat).isFinally()) {
        if (TryWithResourcesProcessor.makeTryWithResource((CatchAllStatement) stat)) {
          return true;
        }
      }

      for (Statement st : new ArrayList<>(stat.getStats())) {
        if (makeTryWithResourceRec(cl, st)) {
          return true;
        }
      }

      return false;
    }
  }

  // J11+
  // Merge all try statements recursively
  private static boolean mergeTrys(Statement root) {
    boolean ret = false;

    if (root.type == Statement.TYPE_TRYCATCH) {
      if (mergeTry((CatchStatement) root)) {
        ret = true;
      }
    }

    for (Statement stat : new ArrayList<>(root.getStats())) {
      ret |= mergeTrys(stat);
    }

    return ret;
  }

  // J11+
  // Merges try with resource statements that are nested within each other, as well as try with resources statements nested in a normal try.
  private static boolean mergeTry(CatchStatement stat) {
    if (stat.getStats().isEmpty()) {
      return false;
    }

    // Get the statement inside of the current try
    Statement inner = stat.getStats().get(0);

    // Check if the inner statement is a try statement
    if (inner.type == Statement.TYPE_TRYCATCH) {
      // Filter on try with resources statements
      if (((CatchStatement)inner).getTryType() == CatchStatement.RESOURCES) {
        // One try inside of the catch

        // Only merge trys that have an inner statement size of 1, a single block
        // TODO: how does this handle nested nullable try stats?
        if (inner.getStats().size() == 1) {
          // Set the outer try to be resources, and initialize
          stat.setTryType(CatchStatement.RESOURCES);
          stat.getResources().addAll(((CatchStatement)inner).getResources());
          stat.getVarDefinitions().addAll(inner.getVarDefinitions());

          // Get inner block of inner try stat
          Statement innerBlock = inner.getStats().get(0);

          // Remove successors as the replaceStatement call will add the appropriate successor
          List<StatEdge> innerEdges = inner.getAllSuccessorEdges();
          for (StatEdge succ : innerBlock.getAllSuccessorEdges()) {
            boolean found = false;
            for (StatEdge innerEdge : innerEdges) {
              if (succ.getDestination() == innerEdge.getDestination() && succ.getType() == innerEdge.getType()) {
                found = true;
                break;
              }
            }

            if (found) {
              innerBlock.removeSuccessor(succ);
            }
          }

          // Replace the inner try statement with the block inside
          stat.replaceStatement(inner, innerBlock);

          return true;
        }
      }
    }

    return false;
  }

  private static boolean collapseTryRec(Statement stat) {
    if (stat.type == Statement.TYPE_TRYCATCH && collapseTry((CatchStatement)stat)) {
      return true;
    }

    for (Statement st : stat.getStats()) {
      if (collapseTryRec(st)) {
        return true;
      }
    }

    return false;
  }

  private static boolean collapseTry(CatchStatement catchStat) {
    Statement parent = catchStat;
    if (parent.getFirst() != null && parent.getFirst().type == Statement.TYPE_SEQUENCE) {
      parent = parent.getFirst();
    }
    if (parent != null && parent.getFirst() != null && parent.getFirst().type == Statement.TYPE_TRYCATCH) {
      CatchStatement toRemove = (CatchStatement)parent.getFirst();

      if (toRemove.getTryType() == CatchStatement.RESOURCES) {
        catchStat.setTryType(CatchStatement.RESOURCES);
        catchStat.getResources().addAll(toRemove.getResources());

        catchStat.getVarDefinitions().addAll(toRemove.getVarDefinitions());
        parent.replaceStatement(toRemove, toRemove.getFirst());

        if (!toRemove.getVars().isEmpty()) {
          for (int i = 0; i < toRemove.getVars().size(); ++i) {
            catchStat.getVars().add(i, toRemove.getVars().get(i));
            catchStat.getExctStrings().add(i, toRemove.getExctStrings().get(i));

            catchStat.getStats().add(i + 1, catchStat.getStats().get(i + 1));
          }
        }
        return true;
      }
    }
    return false;
  }

  // try {
  //   ...
  // } catch (Exception e) {
  //   ...
  // } catch (Exception e) {
  //   ...
  // }
  //
  // into
  // try {
  //   try {
  //     ...
  //   } catch (Exception e) {
  //     ...
  //   }
  // } catch (Exception e) {
  //   ...
  // }
  public static boolean splitTryWithSameCatch(RootStatement root) {
    return splitTryWithSameCatch(root, root);
  }

  private static boolean splitTryWithSameCatch(RootStatement root, Statement stat) {
    boolean ret = false;

    for (Statement st : new ArrayList<>(stat.getStats())) {
      ret |= splitTryWithSameCatch(root, st);
    }

    if (stat.type == Statement.TYPE_TRYCATCH && ((CatchStatement)stat).getTryType() == CatchStatement.NORMAL) {
      CatchStatement catchStat = (CatchStatement) stat;

      Set<List<String>> setExec = new HashSet<>(catchStat.getExctStrings());

      // Check if list of catch headers has a duplicate type
      if (setExec.size() < catchStat.getExctStrings().size()) {
        // To not have to calculate catch dominators, split along every catch type for now
        // TODO: better impl that only splits the needed type

        // Stack of nested statements
        List<Statement> catchStack = new ArrayList<>();
        catchStack.add(stat.getFirst());

        for (int i = 0; i < catchStat.getExctStrings().size(); i++) {
          // Make new trycatch out of the last statement on the stack, as well as the original statement's catch handler
          CatchStatement newCatch = new CatchStatement(catchStack.get(i), stat.getStats().get(i + 1), catchStat.getExctStrings().get(i), catchStat.getVars().get(i));

          newCatch.setAllParent();

          // The statement that we just made is now the topmost on the stack
          catchStack.add(newCatch);
        }

        // Replace the original statement with the nested type
        stat.replaceWith(catchStack.get(catchStack.size() - 1));

        root.addComment(" $FF: Try-catch with two of the same exception type had to be split");

        ret = true;
      }
    }

    return ret;
  }
}
