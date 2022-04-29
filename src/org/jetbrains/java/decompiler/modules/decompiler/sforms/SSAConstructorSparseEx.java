// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.modules.decompiler.sforms;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.modules.decompiler.exps.*;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.FlattenStatementsHelper.FinallyPathWrapper;
import org.jetbrains.java.decompiler.modules.decompiler.stats.CatchAllStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.CatchStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.util.DotExporter;
import org.jetbrains.java.decompiler.util.FastSparseSetFactory;
import org.jetbrains.java.decompiler.util.FastSparseSetFactory.FastSparseSet;
import org.jetbrains.java.decompiler.util.InterpreterUtil;
import org.jetbrains.java.decompiler.util.SFormsFastMapDirect;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map.Entry;

import static org.jetbrains.java.decompiler.modules.decompiler.sforms.VarMapHolder.mergeMaps;

public class SSAConstructorSparseEx {

  // node id, var, version
  private final HashMap<String, SFormsFastMapDirect> inVarVersions = new HashMap<>();

  // node id, var, version (direct branch)
  private final HashMap<String, SFormsFastMapDirect> outVarVersions = new HashMap<>();

  // node id, var, version (negative branch)
  private final HashMap<String, SFormsFastMapDirect> outNegVarVersions = new HashMap<>();

  // node id, var, version
  private final HashMap<String, SFormsFastMapDirect> extraVarVersions = new HashMap<>();

  // (var, version), version
  private final HashMap<VarVersionPair, FastSparseSet<Integer>> phi = new HashMap<>();

  // var, version
  private final HashMap<Integer, Integer> lastversion = new HashMap<>();

  // set factory
  private FastSparseSetFactory<Integer> factory;

  public void splitVariables(RootStatement root, StructMethod mt) {

    FlattenStatementsHelper flatthelper = new FlattenStatementsHelper();
    DirectGraph dgraph = flatthelper.buildDirectGraph(root);

    DotExporter.toDotFile(dgraph, mt, "ssaSplitVariables");

    List<Integer> setInit = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      setInit.add(i);
    }
    factory = new FastSparseSetFactory<>(setInit);

    SFormsFastMapDirect firstmap = createFirstMap(mt);
    extraVarVersions.put(dgraph.first.id, firstmap);

    setCatchMaps(root, dgraph, flatthelper);

    int itteration = 1;
    HashSet<String> updated = new HashSet<>();
    do {
      // System.out.println("~~~~~~~~~~~~~ \r\n"+root.toJava());
      ssaStatements(dgraph, updated, mt, itteration++);
      // System.out.println("~~~~~~~~~~~~~ \r\n"+root.toJava());
    }
    while (!updated.isEmpty());
  }

  private void ssaStatements(DirectGraph dgraph, HashSet<String> updated, StructMethod mt, int itteration) {

    DotExporter.toDotFile(dgraph, mt, "ssaStatements_" + itteration, outVarVersions);

    for (DirectNode node : dgraph.nodes) {

      updated.remove(node.id);
      mergeInVarMaps(node, dgraph);

      SFormsFastMapDirect varmap = inVarVersions.get(node.id);
      VarMapHolder varmaps = VarMapHolder.ofNormal(varmap);

      if (node.exprents != null) {
        for (Exprent expr : node.exprents) {
          varmaps.toNormal(); // make sure we are in normal form
          this.processExprent(node, expr, varmaps);
        }
      }

      boolean this_updated = !mapsEqual(varmaps.getIfTrue(), outVarVersions.get(node.id))
                             || (outNegVarVersions.containsKey(node.id) && !mapsEqual(varmaps.getIfFalse(), outNegVarVersions.get(node.id)));

      if (this_updated) {
        outVarVersions.put(node.id, varmaps.getIfTrue());
        if (dgraph.mapNegIfBranch.containsKey(node.id)) {
          outNegVarVersions.put(node.id, varmaps.getIfFalse());
        }

        // Don't update the node if it wasn't discovered normally, as that can lead to infinite recursion due to bad ordering!
        if (!dgraph.extraNodes.contains(node)) {
          for (DirectNode nd : node.succs) {
            updated.add(nd.id);
          }
        }
      }
    }
  }

  private void processExprent(DirectNode node, Exprent expr, VarMapHolder varmaps) {

    if (expr == null) {
      return;
    }

    assert varmaps.isNormal();

    VarExprent varassign = null;
    boolean finished = false, skipRecursion = false;

    switch (expr.type) {
      case Exprent.EXPRENT_IF: {
        IfExprent ifexpr = (IfExprent)expr;
        this.processExprent(node, ifexpr.getCondition(), varmaps);
        return;
      }
      case Exprent.EXPRENT_ASSIGNMENT:
        AssignmentExprent assexpr = (AssignmentExprent)expr;
        if (assexpr.getCondType() == AssignmentExprent.CONDITION_NONE) {
          Exprent dest = assexpr.getLeft();
          if (dest.type == Exprent.EXPRENT_VAR) {
            varassign = (VarExprent)dest;
          }
        }
        break;
      case Exprent.EXPRENT_FUNCTION:
        FunctionExprent func = (FunctionExprent)expr;
        switch (func.getFuncType()) {
          case FunctionExprent.FUNCTION_IIF: {
            // a ? b : c
            this.processExprent(node, func.getLstOperands().get(0), varmaps);

            VarMapHolder bVarMaps = VarMapHolder.ofNormal(varmaps.getIfTrue());
            this.processExprent(node, func.getLstOperands().get(1), bVarMaps);

            VarMapHolder cVarMaps = VarMapHolder.ofNormal(varmaps.getIfFalse());
            this.processExprent(node, func.getLstOperands().get(2), cVarMaps);

            if (bVarMaps.isNormal() && cVarMaps.isNormal()) {
              varmaps.setNormal(mergeMaps(bVarMaps.getNormal(), cVarMaps.getNormal()));
            } else {
              bVarMaps.makeFullyMutable();
              bVarMaps.mergeIfTrue(cVarMaps.getIfTrue());
              bVarMaps.mergeIfFalse(cVarMaps.getIfFalse());

              varmaps.set(bVarMaps);
            }

            finished = true;
            break;
          }
          case FunctionExprent.FUNCTION_CADD: {
            this.processExprent(node, func.getLstOperands().get(0), varmaps);

            VarMapHolder rightHandSideVarMaps = VarMapHolder.ofNormal(varmaps.getIfTrue());

            this.processExprent(node, func.getLstOperands().get(1), rightHandSideVarMaps);

            // true map
            varmaps.setIfTrue(rightHandSideVarMaps.getIfTrue());
            // false map
            varmaps.mergeIfFalse(rightHandSideVarMaps.getIfFalse());

            finished = true;
            break;
          }
          case FunctionExprent.FUNCTION_COR: {
            this.processExprent(node, func.getLstOperands().get(0), varmaps);

            VarMapHolder rightHandSideVarMaps = VarMapHolder.ofNormal(varmaps.getIfTrue());

            this.processExprent(node, func.getLstOperands().get(1), rightHandSideVarMaps);

            // true map
            varmaps.mergeIfTrue(rightHandSideVarMaps.getIfTrue());
            // false map
            varmaps.setIfFalse(rightHandSideVarMaps.getIfFalse());

            finished = true;
            break;
          }
          case FunctionExprent.FUNCTION_BOOL_NOT: {
            this.processExprent(node, func.getLstOperands().get(0), varmaps);
            varmaps.swap();

            finished = true;
            break;
          }
          case FunctionExprent.FUNCTION_INSTANCEOF: {
            processExprent(node, func.getLstOperands().get(0), varmaps);
            varmaps.toNormal();

            if(func.getLstOperands().size() == 3) {
              // pattern matching
              varmaps.makeFullyMutable();

              varassign = (VarExprent)func.getLstOperands().get(2);
              int varindex = varassign.getIndex();

              if (varassign.getVersion() == 0) {
                // get next version
                int nextver = getNextFreeVersion(varindex);

                // set version
                varassign.setVersion(nextver);

                setCurrentVar(varmaps.getIfTrue(), varindex, nextver);
              }
              else {
                setCurrentVar(varmaps.getIfTrue(), varindex, varassign.getVersion());
              }

              // I don't understand why this is needed
              varmaps.mergeIfFalse(varmaps.getIfTrue());

              // skipRecursion = true;
              finished = true;
            } else {
              finished = true;
            }
            break;
          }
        }
    }

    if (finished) {
      return;
    }

    // Foreach init node- mark as assignment!
    if (varassign == null && node.type == DirectNode.NodeType.FOREACH_VARDEF && node.exprents.get(0).type == Exprent.EXPRENT_VAR) {
      varassign = (VarExprent) node.exprents.get(0);
    }


    List<Exprent> lst = expr.getAllExprents();
    lst.remove(varassign);

    for (Exprent ex : lst) {
      processExprent(node, ex, varmaps);
      varmaps.toNormal();
    }

    SFormsFastMapDirect varmap = varmaps.getNormal();

    if (varassign != null) {

      int varindex = varassign.getIndex();

      if (varassign.getVersion() == 0) {
        // get next version
        int nextver = getNextFreeVersion(varindex);

        // set version
        varassign.setVersion(nextver);

        setCurrentVar(varmap, varindex, nextver);
      }
      else {
        setCurrentVar(varmap, varindex, varassign.getVersion());
      }
    }
    else if (expr.type == Exprent.EXPRENT_VAR) {

      VarExprent vardest = (VarExprent)expr;
      int varindex = vardest.getIndex();
      FastSparseSet<Integer> vers = varmap.get(varindex);

      int cardinality = vers != null ? vers.getCardinality() : 0;
      if (cardinality == 1) { // == 1
        // set version
        int it = vers.iterator().next();
        vardest.setVersion(it);
      }
      else if (cardinality == 2) { // size > 1
        int current_vers = vardest.getVersion();

        VarVersionPair currpaar = new VarVersionPair(varindex, current_vers);
        if (current_vers != 0 && phi.containsKey(currpaar)) {
          setCurrentVar(varmap, varindex, current_vers);
          // update phi node
          phi.get(currpaar).union(vers);
        }
        else {
          // increase version
          int nextver = getNextFreeVersion(varindex);
          // set version
          vardest.setVersion(nextver);

          setCurrentVar(varmap, varindex, nextver);
          // create new phi node
          phi.put(new VarVersionPair(varindex, nextver), vers);
        }
      } // 0 means uninitialized variable
      else if (cardinality == 0) {
        if (vardest.getVersion() != 0) {
          setCurrentVar(varmap, varindex, vardest.getVersion());
        }
        else {
          int nextver = getNextFreeVersion(varindex);
          vardest.setVersion(nextver);
          setCurrentVar(varmap, varindex, nextver);
        }
      }
    }
  }

  private int getNextFreeVersion(int var) {
    Integer nextver = lastversion.get(var);
    if (nextver == null) {
      nextver = 1;
    }
    else {
      nextver++;
    }
    lastversion.put(var, nextver);
    return nextver;
  }

  private void mergeInVarMaps(DirectNode node, DirectGraph dgraph) {

    SFormsFastMapDirect mapNew = new SFormsFastMapDirect();

    for (DirectNode pred : node.preds) {
      SFormsFastMapDirect mapOut = getFilteredOutMap(node.id, pred.id, dgraph, node.id);
      if (mapNew.isEmpty()) {
        mapNew = mapOut.getCopy();
      }
      else {
        mergeMaps(mapNew, mapOut);
      }
    }

    if (extraVarVersions.containsKey(node.id)) {
      SFormsFastMapDirect mapExtra = extraVarVersions.get(node.id);
      if (mapNew.isEmpty()) {
        mapNew = mapExtra.getCopy();
      }
      else {
        mergeMaps(mapNew, mapExtra);
      }
    }

    inVarVersions.put(node.id, mapNew);
  }

  private SFormsFastMapDirect getFilteredOutMap(String nodeid, String predid, DirectGraph dgraph, String destid) {

    SFormsFastMapDirect mapNew = new SFormsFastMapDirect();

    if (nodeid.equals(dgraph.mapNegIfBranch.get(predid))) {
      if (outNegVarVersions.containsKey(predid)) {
        mapNew = outNegVarVersions.get(predid).getCopy();
      }
    }
    else if (outVarVersions.containsKey(predid)) {
      mapNew = outVarVersions.get(predid).getCopy();
    }

    boolean isFinallyExit = dgraph.mapShortRangeFinallyPaths.containsKey(predid);

    if (isFinallyExit && !mapNew.isEmpty()) {

      SFormsFastMapDirect mapNewTemp = mapNew.getCopy();

      SFormsFastMapDirect mapTrueSource = new SFormsFastMapDirect();

      String exceptionDest = dgraph.mapFinallyMonitorExceptionPathExits.get(predid);
      boolean isExceptionMonitorExit = (exceptionDest != null && !nodeid.equals(exceptionDest));

      HashSet<String> setLongPathWrapper = new HashSet<>();
      for (FinallyPathWrapper finwraplong : dgraph.mapLongRangeFinallyPaths.get(predid)) {
        setLongPathWrapper.add(finwraplong.destination + "##" + finwraplong.source);
      }

      for (FinallyPathWrapper finwrap : dgraph.mapShortRangeFinallyPaths.get(predid)) {
        SFormsFastMapDirect map;

        boolean recFinally = dgraph.mapShortRangeFinallyPaths.containsKey(finwrap.source);

        if (recFinally) {
          // recursion
          map = getFilteredOutMap(finwrap.entry, finwrap.source, dgraph, destid);
        }
        else {
          if (finwrap.entry.equals(dgraph.mapNegIfBranch.get(finwrap.source))) {
            map = outNegVarVersions.get(finwrap.source);
          }
          else {
            map = outVarVersions.get(finwrap.source);
          }
        }

        // false path?
        boolean isFalsePath;

        if (recFinally) {
          isFalsePath = !finwrap.destination.equals(nodeid);
        }
        else {
          isFalsePath = !setLongPathWrapper.contains(destid + "##" + finwrap.source);
        }

        if (isFalsePath) {
          mapNewTemp.complement(map);
        }
        else {
          if (mapTrueSource.isEmpty()) {
            if (map != null) {
              mapTrueSource = map.getCopy();
            }
          }
          else {
            mergeMaps(mapTrueSource, map);
          }
        }
      }

      if (isExceptionMonitorExit) {

        mapNew = mapTrueSource;
      }
      else {

        mapNewTemp.union(mapTrueSource);

        SFormsFastMapDirect oldInMap = inVarVersions.get(nodeid);
        if (oldInMap != null) {
          mapNewTemp.union(oldInMap);
        }

        mapNew.intersection(mapNewTemp);
      }
    }

    return mapNew;
  }

  private static boolean mapsEqual(SFormsFastMapDirect map1, SFormsFastMapDirect map2) {

    if (map1 == null) {
      return map2 == null;
    }
    else if (map2 == null) {
      return false;
    }

    if (map1.size() != map2.size()) {
      return false;
    }

    for (Entry<Integer, FastSparseSet<Integer>> ent2 : map2.entryList()) {
      if (!InterpreterUtil.equalObjects(map1.get(ent2.getKey()), ent2.getValue())) {
        return false;
      }
    }

    return true;
  }

  private void setCurrentVar(SFormsFastMapDirect varmap, int var, int vers) {
    FastSparseSet<Integer> set = factory.spawnEmptySet();
    set.add(vers);
    varmap.put(var, set);
  }

  private void setCatchMaps(Statement stat, DirectGraph dgraph, FlattenStatementsHelper flatthelper) {

    SFormsFastMapDirect map;

    switch (stat.type) {
      case Statement.TYPE_CATCHALL:
      case Statement.TYPE_TRYCATCH:

        List<VarExprent> lstVars;
        if (stat.type == Statement.TYPE_CATCHALL) {
          lstVars = ((CatchAllStatement)stat).getVars();
        }
        else {
          lstVars = ((CatchStatement)stat).getVars();
        }

        for (int i = 1; i < stat.getStats().size(); i++) {
          int varindex = lstVars.get(i - 1).getIndex();
          int version = getNextFreeVersion(varindex); // == 1

          map = new SFormsFastMapDirect();
          setCurrentVar(map, varindex, version);

          extraVarVersions.put(dgraph.nodes.getWithKey(flatthelper.getMapDestinationNodes().get(stat.getStats().get(i).id)[0]).id, map);
        }
    }

    for (Statement st : stat.getStats()) {
      setCatchMaps(st, dgraph, flatthelper);
    }
  }

  private SFormsFastMapDirect createFirstMap(StructMethod mt) {
    boolean thisvar = !mt.hasModifier(CodeConstants.ACC_STATIC);

    MethodDescriptor md = MethodDescriptor.parseDescriptor(mt.getDescriptor());

    int paramcount = md.params.length + (thisvar ? 1 : 0);

    int varindex = 0;
    SFormsFastMapDirect map = new SFormsFastMapDirect();
    for (int i = 0; i < paramcount; i++) {
      int version = getNextFreeVersion(varindex); // == 1

      FastSparseSet<Integer> set = factory.spawnEmptySet();
      set.add(version);
      map.put(varindex, set);

      if (thisvar) {
        if (i == 0) {
          varindex++;
        }
        else {
          varindex += md.params[i - 1].stackSize;
        }
      }
      else {
        varindex += md.params[i].stackSize;
      }
    }

    return map;
  }

  public HashMap<VarVersionPair, FastSparseSet<Integer>> getPhi() {
    return phi;
  }
}
