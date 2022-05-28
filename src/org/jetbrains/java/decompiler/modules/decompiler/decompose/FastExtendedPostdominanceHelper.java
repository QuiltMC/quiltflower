// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.modules.decompiler.decompose;

import org.jetbrains.java.decompiler.modules.decompiler.StatEdge;
import org.jetbrains.java.decompiler.modules.decompiler.stats.GeneralStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.util.FastFixedSetFactory;
import org.jetbrains.java.decompiler.util.FastFixedSetFactory.FastFixedSet;
import org.jetbrains.java.decompiler.util.InterpreterUtil;

import java.util.*;
import java.util.Map.Entry;

public class FastExtendedPostdominanceHelper {

  private List<Statement> lstReversePostOrderList;

  private HashMap<Integer, FastFixedSet<Integer>> mapSupportPoints = new LinkedHashMap<>();

  private final HashMap<Integer, FastFixedSet<Integer>> mapExtPostdominators = new LinkedHashMap<>();

  private Statement statement;

  private FastFixedSetFactory<Integer> factory;

  public HashMap<Integer, Set<Integer>> getExtendedPostdominators(Statement statement) {
    if (!(statement instanceof GeneralStatement)) {
      throw new IllegalStateException("Cannot find extended post dominators of non generalized statement");
    }

    this.statement = statement;

    HashSet<Integer> set = new LinkedHashSet<>();
    for (Statement st : statement.getStats()) {
      set.add(st.id);
    }
    this.factory = new FastFixedSetFactory<>(set);

    lstReversePostOrderList = statement.getReversePostOrderList();

    //		try {
    //			DotExporter.toDotFile(statement, new File("c:\\Temp\\stat1.dot"));
    //		} catch (Exception ex) {
    //			ex.printStackTrace();
    //		}

    calcDefaultReachableSets();

    removeErroneousNodes();

    DominatorTreeExceptionFilter filter = new DominatorTreeExceptionFilter(statement);
    filter.initialize();

    filterOnExceptionRanges(filter);

    filterOnDominance(filter);

    Set<Entry<Integer, FastFixedSet<Integer>>> entries = mapExtPostdominators.entrySet();
    HashMap<Integer, Set<Integer>> res = new HashMap<>(entries.size());
    for (Entry<Integer, FastFixedSet<Integer>> entry : entries) {
      List<Integer> lst = new ArrayList<>(entry.getValue().toPlainSet());
      Collections.sort(lst); // Order Matters!
      res.put(entry.getKey(), new LinkedHashSet<>(lst));
    }

    return res;
  }


  private void filterOnDominance(DominatorTreeExceptionFilter filter) {

    DominatorEngine engine = filter.getDomEngine();
    LinkedList<Statement> stack = new LinkedList<>();
    LinkedList<FastFixedSet<Integer>> stackPath = new LinkedList<>();
    Set<Statement> setVisited = new HashSet<>();

    for (int head : new HashSet<>(mapExtPostdominators.keySet())) {

      FastFixedSet<Integer> setPostdoms = mapExtPostdominators.get(head);

      stack.clear();
      stackPath.clear();

      stack.add(statement.getStats().getWithKey(head));
      stackPath.add(factory.createEmptySet());

      setVisited.clear();

      setVisited.add(stack.getFirst());

      while (!stack.isEmpty()) {

        Statement stat = stack.removeFirst();
        FastFixedSet<Integer> path = stackPath.removeFirst();

        if (!setPostdoms.containsKey(stat.id)) {
          throw new IllegalStateException("Inconsistent statement structure!");
        }

        if (setPostdoms.contains(stat.id)) {
          path.add(stat.id);
        }

        if (path.contains(setPostdoms)) {
          continue;
        }

        if(!engine.isDominator(stat.id, head)) {
          setPostdoms.complement(path);
          continue;
        }

        for (StatEdge edge : stat.getSuccessorEdges(StatEdge.TYPE_REGULAR | StatEdge.TYPE_CONTINUE)) {

          if (edge.getType() == StatEdge.TYPE_CONTINUE && edge.getDestination() != this.statement) {
            continue;
          }

          Statement destination = (edge.getType() == StatEdge.TYPE_CONTINUE && edge.getDestination() == this.statement) ?
            edge.getDestination().getFirst() : edge.getDestination();

          if(!setVisited.contains(destination)) {

            stack.add(destination);
            stackPath.add(path.getCopy());

            setVisited.add(destination);
          }
        }
      }

      if (setPostdoms.isEmpty()) {
        mapExtPostdominators.remove(head);
      }
    }
  }

  private void filterOnExceptionRanges(DominatorTreeExceptionFilter filter) {
    for (int head : new HashSet<>(mapExtPostdominators.keySet())) {
      FastFixedSet<Integer> set = mapExtPostdominators.get(head);
      for (Iterator<Integer> it = set.iterator(); it.hasNext(); ) {
        if (!filter.acceptStatementPair(head, it.next())) {
          it.remove();
        }
      }
      if (set.isEmpty()) {
        mapExtPostdominators.remove(head);
      }
    }
  }

  private void removeErroneousNodes() {
    mapSupportPoints = new HashMap<>();

    calcReachabilitySuppPointsEx();

    iterateReachability((node, mapSets) -> {
      Integer nodeid = node.id;

      FastFixedSet<Integer> setReachability = mapSets.get(nodeid);
      List<FastFixedSet<Integer>> lstPredSets = new ArrayList<>();

      for (StatEdge prededge : node.getPredecessorEdges(StatEdge.TYPE_REGULAR)) {
        FastFixedSet<Integer> setPred = mapSets.get(prededge.getSource().id);
        if (setPred == null) {
          setPred = mapSupportPoints.get(prededge.getSource().id);
        }

        // setPred cannot be empty as it is a reachability set
        lstPredSets.add(setPred);
      }

      for (int id : setReachability) {

        FastFixedSet<Integer> setReachabilityCopy = setReachability.getCopy();

        FastFixedSet<Integer> setIntersection = factory.createEmptySet();
        boolean isIntersectionInitialized = false;

        for (FastFixedSet<Integer> predset : lstPredSets) {
          if (predset.contains(id)) {
            if (!isIntersectionInitialized) {
              setIntersection.union(predset);
              isIntersectionInitialized = true;
            }
            else {
              setIntersection.intersection(predset);
            }
          }
        }

        if (nodeid != id) {
          setIntersection.add(nodeid);
        }
        else {
          setIntersection.remove(nodeid);
        }

        setReachabilityCopy.complement(setIntersection);

        mapExtPostdominators.get(id).complement(setReachabilityCopy);
      }

      return false;
    }, StatEdge.TYPE_REGULAR);

    // exception handlers cannot be postdominator nodes
    // TODO: replace with a standard set?
    FastFixedSet<Integer> setHandlers = factory.createEmptySet();
    boolean handlerfound = false;

    for (Statement stat : statement.getStats()) {
      if (stat.getPredecessorEdges(Statement.STATEDGE_DIRECT_ALL).isEmpty() &&
          !stat.getPredecessorEdges(StatEdge.TYPE_EXCEPTION).isEmpty()) { // exception handler
        setHandlers.add(stat.id);
        handlerfound = true;
      }
    }

    if (handlerfound) {
      for (FastFixedSet<Integer> set : mapExtPostdominators.values()) {
        set.complement(setHandlers);
      }
    }
  }

  private void calcDefaultReachableSets() {
    int edgetype = StatEdge.TYPE_REGULAR | StatEdge.TYPE_EXCEPTION;

    calcReachabilitySuppPoints(edgetype);

    for (Statement stat : statement.getStats()) {
      mapExtPostdominators.put(stat.id, factory.createEmptySet());
    }

    iterateReachability((node, mapSets) -> {
      Integer nodeid = node.id;
      FastFixedSet<Integer> setReachability = mapSets.get(nodeid);

      for (int id : setReachability) {
        mapExtPostdominators.get(id).add(nodeid);
      }

      return false;
    }, edgetype);
  }

  private void calcReachabilitySuppPoints(final int edgetype) {
    iterateReachability((node, mapSets) -> {
      // consider to be a support point
      for (StatEdge sucedge : node.getAllSuccessorEdges()) {
        if ((sucedge.getType() & edgetype) != 0) {
          if (mapSets.containsKey(sucedge.getDestination().id)) {
            FastFixedSet<Integer> setReachability = mapSets.get(node.id);

            if (!InterpreterUtil.equalObjects(setReachability, mapSupportPoints.get(node.id))) {
              mapSupportPoints.put(node.id, setReachability);
              return true;
            }
          }
        }
      }

      return false;
    }, edgetype);
  }

  // TODO: add continue directly to regular reachability points?
  private void calcReachabilitySuppPointsEx() {
    iterateReachability((node, mapSets) -> {
      // consider to be a support point
      for (StatEdge sucedge : node.getAllSuccessorEdges()) {
        if ((sucedge.getType() & StatEdge.TYPE_REGULAR) != 0 || ((sucedge.getType() & StatEdge.TYPE_CONTINUE) != 0 && sucedge.getDestination() == this.statement)) {
          Statement destination = (sucedge.getType() == StatEdge.TYPE_CONTINUE && sucedge.getDestination() == this.statement)
            ? sucedge.getDestination().getFirst() : sucedge.getDestination();

          if (mapSets.containsKey(destination.id)) {
            FastFixedSet<Integer> setReachability = mapSets.get(node.id);

            if (!InterpreterUtil.equalObjects(setReachability, mapSupportPoints.get(node.id))) {
              mapSupportPoints.put(node.id, setReachability);
              return true;
            }
          }
        }
      }

      return false;
    }, StatEdge.TYPE_REGULAR);
  }

  private void iterateReachability(IReachabilityAction action, int edgetype) {
    HashMap<Integer, FastFixedSet<Integer>> mapSets = new HashMap<>();

    while (true) {
      boolean iterate = false;

      mapSets.clear();

      for (Statement stat : lstReversePostOrderList) {

        FastFixedSet<Integer> set = factory.createEmptySet();
        set.add(stat.id);

        for (StatEdge prededge : stat.getAllPredecessorEdges()) {
          if ((prededge.getType() & edgetype) != 0) {
            Statement pred = prededge.getSource();

            FastFixedSet<Integer> setPred = mapSets.get(pred.id);
            if (setPred == null) {
              setPred = mapSupportPoints.get(pred.id);
            }

            if (setPred != null) {
              set.union(setPred);
            }
          }
        }

        mapSets.put(stat.id, set);

        if (action != null) {
          iterate |= action.action(stat, mapSets);
        }

        // remove reachability information of fully processed nodes (saves memory)
        for (StatEdge prededge : stat.getAllPredecessorEdges()) {
          if ((prededge.getType() & edgetype) != 0) {
            Statement pred = prededge.getSource();

            if (mapSets.containsKey(pred.id)) {
              boolean remstat = true;
              for (StatEdge sucedge : pred.getAllSuccessorEdges()) {
                if ((sucedge.getType() & edgetype) != 0) {
                  if (!mapSets.containsKey(sucedge.getDestination().id)) {
                    remstat = false;
                    break;
                  }
                }
              }

              if (remstat) {
                mapSets.put(pred.id, null);
              }
            }
          }
        }
      }

      if (!iterate) {
        break;
      }
    }
  }


  private interface IReachabilityAction {
    boolean action(Statement node, HashMap<Integer, FastFixedSet<Integer>> mapSets);
  }
}
