// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.modules.decompiler.sforms;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.modules.decompiler.exps.*;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.FlattenStatementsHelper.FinallyPathWrapper;
import org.jetbrains.java.decompiler.modules.decompiler.stats.*;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionEdge;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionNode;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionsGraph;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.util.DotExporter;
import org.jetbrains.java.decompiler.util.FastSparseSetFactory;
import org.jetbrains.java.decompiler.util.FastSparseSetFactory.FastSparseSet;
import org.jetbrains.java.decompiler.util.InterpreterUtil;
import org.jetbrains.java.decompiler.util.SFormsFastMapDirect;
import org.jetbrains.java.decompiler.util.VBStyleCollection;

import java.util.*;
import java.util.Map.Entry;

import static org.jetbrains.java.decompiler.modules.decompiler.sforms.VarMapHolder.mergeMaps;

public class SSAUConstructorSparseEx {

  // node id, var, version
  private final HashMap<String, SFormsFastMapDirect> inVarVersions = new HashMap<>();
  //private HashMap<String, HashMap<Integer, FastSet<Integer>>> inVarVersions = new HashMap<String, HashMap<Integer, FastSet<Integer>>>();

  // node id, var, version (direct branch)
  private final HashMap<String, SFormsFastMapDirect> outVarVersions = new HashMap<>();
  //private HashMap<String, HashMap<Integer, FastSet<Integer>>> outVarVersions = new HashMap<String, HashMap<Integer, FastSet<Integer>>>();

  // node id, var, version (negative branch)
  private final HashMap<String, SFormsFastMapDirect> outNegVarVersions = new HashMap<>();
  //private HashMap<String, HashMap<Integer, FastSet<Integer>>> outNegVarVersions = new HashMap<String, HashMap<Integer, FastSet<Integer>>>();

  // node id, var, version
  private final HashMap<String, SFormsFastMapDirect> extraVarVersions = new HashMap<>();
  //private HashMap<String, HashMap<Integer, FastSet<Integer>>> extraVarVersions = new HashMap<String, HashMap<Integer, FastSet<Integer>>>();

  // var, version
  private final HashMap<Integer, Integer> lastversion = new HashMap<>();

  // version, protected ranges (catch, finally)
  private final HashMap<VarVersionPair, Integer> mapVersionFirstRange = new HashMap<>();

  // version, version
  private final HashMap<VarVersionPair, VarVersionPair> phantomppnodes = new HashMap<>(); // ++ and --

  // node.id, version, version
  private final HashMap<String, HashMap<VarVersionPair, VarVersionPair>> phantomexitnodes =
    new HashMap<>(); // finally exits

  // versions memory dependencies
  private final VarVersionsGraph ssuversions = new VarVersionsGraph();

  // field access vars (exprent id, var id)
  private final HashMap<Integer, Integer> mapFieldVars = new HashMap<>();

  // field access counter
  private int fieldvarcounter = -1;

  // set factory
  private FastSparseSetFactory<Integer> factory;

  // track assignments for finding effectively final vars (left var, right var)
  private HashMap<VarVersionPair, VarVersionPair> varAssignmentMap = new HashMap<>();

  public void splitVariables(RootStatement root, StructMethod mt) {

    FlattenStatementsHelper flatthelper = new FlattenStatementsHelper();
    DirectGraph dgraph = flatthelper.buildDirectGraph(root);

    DotExporter.toDotFile(dgraph, mt, "ssauSplitVariables");
    
    List<Integer> setInit = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      setInit.add(i);
    }
    factory = new FastSparseSetFactory<>(setInit);

    extraVarVersions.put(dgraph.first.id, createFirstMap(mt, root));

    setCatchMaps(root, dgraph, flatthelper);

    int iteration = 1;
    HashSet<String> updated = new HashSet<>();
    do {
      //			System.out.println("~~~~~~~~~~~~~ \r\n"+root.toJava());
      ssaStatements(dgraph, updated, false, mt, iteration++);
      //			System.out.println("~~~~~~~~~~~~~ \r\n"+root.toJava());
    }
    while (!updated.isEmpty());


    ssaStatements(dgraph, updated, true, mt, iteration);

    ssuversions.initDominators();

    DotExporter.toDotFile(ssuversions, mt, "ssauVarVer" + xxx++, varAssignmentMap);
  }

  static private int xxx = 0;

  private void ssaStatements(DirectGraph dgraph, HashSet<String> updated, boolean calcLiveVars, StructMethod mt, int iteration) {

    DotExporter.toDotFile(dgraph, mt, "ssauStatements_" + iteration);

    for (DirectNode node : dgraph.nodes) {

      updated.remove(node.id);
      mergeInVarMaps(node, dgraph);

      SFormsFastMapDirect varmap = inVarVersions.get(node.id);
      VarMapHolder varmaps = VarMapHolder.ofNormal(varmap);

      if (node.exprents != null) {
        boolean skip = true;
        for (Exprent expr : node.exprents) {
            varmaps.toNormal(); // make sure we are in normal form
          processExprent(node, expr, varmaps, node.statement, calcLiveVars);
        }
      }

      // quick solution: 'dummy' field variables should not cross basic block borders (otherwise problems e.g. with finally loops - usage without assignment in a loop)
      // For the full solution consider adding a dummy assignment at the entry point of the method
      boolean allow_field_propagation = node.succs.isEmpty() || (node.succs.size() == 1 && node.succs.get(0).preds.size() == 1);

      if (!allow_field_propagation) {
        varmaps.getIfTrue().removeAllFields();
        varmaps.getIfFalse().removeAllFields();
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


  private void processExprent(DirectNode node, Exprent expr, VarMapHolder varmaps, Statement stat, boolean calcLiveVars) {

    if (expr == null) {
      return;
    }

    assert varmaps.isNormal();


    VarExprent varassign = null;
    boolean finished = false;

    switch (expr.type) {
      case Exprent.EXPRENT_IF: {
        IfExprent ifexpr = (IfExprent)expr;
        this.processExprent(node, ifexpr.getCondition(), varmaps, stat, calcLiveVars);
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
            this.processExprent(node, func.getLstOperands().get(0), varmaps, stat, calcLiveVars);

            VarMapHolder bVarMaps = VarMapHolder.ofNormal(varmaps.getIfTrue());
            this.processExprent(node, func.getLstOperands().get(1), bVarMaps, stat, calcLiveVars);

            VarMapHolder cVarMaps = VarMapHolder.ofNormal(varmaps.getIfFalse());
            this.processExprent(node, func.getLstOperands().get(2), cVarMaps, stat, calcLiveVars);

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
            this.processExprent(node, func.getLstOperands().get(0), varmaps, stat, calcLiveVars);

            VarMapHolder rightHandSideVarMaps = VarMapHolder.ofNormal(varmaps.getIfTrue());

            this.processExprent(node, func.getLstOperands().get(1), rightHandSideVarMaps, stat, calcLiveVars);

            // true map
            varmaps.setIfTrue(rightHandSideVarMaps.getIfTrue());
            // false map
            varmaps.mergeIfFalse(rightHandSideVarMaps.getIfFalse());

            finished = true;
            break;
          }
          case FunctionExprent.FUNCTION_COR: {
            this.processExprent(node, func.getLstOperands().get(0), varmaps, stat, calcLiveVars);

            VarMapHolder rightHandSideVarMaps = VarMapHolder.ofNormal(varmaps.getIfTrue());

            this.processExprent(node, func.getLstOperands().get(1), rightHandSideVarMaps, stat, calcLiveVars);

            // true map
            varmaps.mergeIfTrue(rightHandSideVarMaps.getIfTrue());
            // false map
            varmaps.setIfFalse(rightHandSideVarMaps.getIfFalse());

            finished = true;
            break;
          }
          case FunctionExprent.FUNCTION_BOOL_NOT: {
            this.processExprent(node, func.getLstOperands().get(0), varmaps, stat, calcLiveVars);
            varmaps.swap();

            finished = true;
            break;
          }
          case FunctionExprent.FUNCTION_INSTANCEOF: {
            this.processExprent(node, func.getLstOperands().get(0), varmaps, stat, calcLiveVars);
            varmaps.toNormal();

            if(func.getLstOperands().size() == 3) {
              // pattern matching
              varmaps.makeFullyMutable();

              varassign = (VarExprent) func.getLstOperands().get(2);

              int varindex = varassign.getIndex();

              if (varassign.getVersion() == 0) {
                // get next version
                int nextver = getNextFreeVersion(varindex, stat);

                // set version
                varassign.setVersion(nextver);

                // ssu graph
                ssuversions.createNode(new VarVersionPair(varindex, nextver), varassign.getLVT());

                setCurrentVar(varmaps.getIfTrue(), varindex, nextver);
              } else {
                if (calcLiveVars) {
                  varMapToGraph(new VarVersionPair(varindex, varassign.getVersion()), varmaps.getIfTrue());
                }

                setCurrentVar(varmaps.getIfTrue(), varindex, varassign.getVersion());
              }


              if (func.getLstOperands().get(0).type == Exprent.EXPRENT_VAR) {
                VarVersionPair rightpaar = ((VarExprent) func.getLstOperands().get(0)).getVarVersionPair();
                varAssignmentMap.put(varassign.getVarVersionPair(), rightpaar);
              } else if (func.getLstOperands().get(0).type == Exprent.EXPRENT_FIELD) {
                int index = mapFieldVars.get(((FieldExprent) func.getLstOperands().get(0)).id);
                VarVersionPair rightpaar = new VarVersionPair(index, 0);
                varAssignmentMap.put(varassign.getVarVersionPair(), rightpaar);
              }
            }

            return;
          }
        }
    }

    // Foreach init node- mark as assignment!
    if (varassign == null && node.type == DirectNode.NodeType.FOREACH_VARDEF && node.exprents.get(0).type == Exprent.EXPRENT_VAR) {
      varassign = (VarExprent) node.exprents.get(0);
    }

    if (!finished) {
      List<Exprent> lst = expr.getAllExprents();
      lst.remove(varassign);

      for (Exprent ex : lst) {
        processExprent(node, ex, varmaps, stat, calcLiveVars);
        varmaps.toNormal();
      }
    }


    SFormsFastMapDirect varmap = finished ? null : varmaps.getNormal();

    // field access
    if (expr.type == Exprent.EXPRENT_FIELD) {

      int index;
      if (mapFieldVars.containsKey(expr.id)) {
        index = mapFieldVars.get(expr.id);
      } else {
        index = fieldvarcounter--;
        mapFieldVars.put(expr.id, index);

        // ssu graph
        ssuversions.createNode(new VarVersionPair(index, 1));
      }

      setCurrentVar(varmap, index, 1);
    } else if (expr.type == Exprent.EXPRENT_INVOCATION ||
             (expr.type == Exprent.EXPRENT_ASSIGNMENT && ((AssignmentExprent)expr).getLeft().type == Exprent.EXPRENT_FIELD) ||
             (expr.type == Exprent.EXPRENT_NEW && ((NewExprent)expr).getNewType().type == CodeConstants.TYPE_OBJECT) ||
             expr.type == Exprent.EXPRENT_FUNCTION) {

      boolean ismmpp = true;

      if (expr.type == Exprent.EXPRENT_FUNCTION) {

        ismmpp = false;

        FunctionExprent fexpr = (FunctionExprent)expr;
        if (fexpr.getFuncType() >= FunctionExprent.FUNCTION_IMM && fexpr.getFuncType() <= FunctionExprent.FUNCTION_PPI) {
          if (fexpr.getLstOperands().get(0).type == Exprent.EXPRENT_FIELD) {
            ismmpp = true;
          }
        }
      }

      if (ismmpp) {
        varmap.removeAllFields();
      }
    }


    if (varassign != null) {

      int varindex = varassign.getIndex();

      if (varassign.getVersion() == 0) {
        // get next version
        int nextver = getNextFreeVersion(varindex, stat);

        // set version
        varassign.setVersion(nextver);

        // ssu graph
        ssuversions.createNode(new VarVersionPair(varindex, nextver), varassign.getLVT());

        setCurrentVar(varmap, varindex, nextver);
      } else {
        if (calcLiveVars) {
          varMapToGraph(new VarVersionPair(varindex, varassign.getVersion()), varmap);
        }

        setCurrentVar(varmap, varindex, varassign.getVersion());
      }

      if (expr.type == Exprent.EXPRENT_ASSIGNMENT) {
        AssignmentExprent assexpr = (AssignmentExprent) expr;

        if (assexpr.getRight().type == Exprent.EXPRENT_VAR) {
          VarVersionPair rightpaar = ((VarExprent) assexpr.getRight()).getVarVersionPair();
          varAssignmentMap.put(varassign.getVarVersionPair(), rightpaar);
        } else if (assexpr.getRight().type == Exprent.EXPRENT_FIELD) {
          int index = mapFieldVars.get(((FieldExprent) assexpr.getRight()).id);
          VarVersionPair rightpaar = new VarVersionPair(index, 0);
          varAssignmentMap.put(varassign.getVarVersionPair(), rightpaar);
        }
      }
    }
    else if (expr.type == Exprent.EXPRENT_FUNCTION) { // MM or PP function
      FunctionExprent func = (FunctionExprent)expr;

      switch (func.getFuncType()) {
        case FunctionExprent.FUNCTION_IMM:
        case FunctionExprent.FUNCTION_MMI:
        case FunctionExprent.FUNCTION_IPP:
        case FunctionExprent.FUNCTION_PPI:

          if (func.getLstOperands().get(0).type == Exprent.EXPRENT_VAR) {
            VarExprent var = (VarExprent)func.getLstOperands().get(0);
            int varindex = var.getIndex();
            VarVersionPair varpaar = new VarVersionPair(varindex, var.getVersion());

            // ssu graph
            VarVersionPair phantomver = phantomppnodes.get(varpaar);
            if (phantomver == null) {
              // get next version
              int nextver = getNextFreeVersion(varindex, null);
              phantomver = new VarVersionPair(varindex, nextver);
              //ssuversions.createOrGetNode(phantomver);
              ssuversions.createNode(phantomver);

              VarVersionNode vernode = ssuversions.nodes.getWithKey(varpaar);

              FastSparseSet<Integer> vers = factory.spawnEmptySet();
              if (vernode.preds.size() == 1) {
                vers.add(vernode.preds.iterator().next().source.version);
              }
              else {
                for (VarVersionEdge edge : vernode.preds) {
                  vers.add(edge.source.preds.iterator().next().source.version);
                }
              }
              vers.add(nextver);
              createOrUpdatePhiNode(varpaar, vers, stat);
              phantomppnodes.put(varpaar, phantomver);
            }
            if (calcLiveVars) {
              varMapToGraph(varpaar, varmap);
            }
            setCurrentVar(varmap, varindex, var.getVersion());
          }
      }
    }
    else if (expr.type == Exprent.EXPRENT_VAR) {

      VarExprent vardest = (VarExprent)expr;

      int varindex = vardest.getIndex();
      int current_vers = vardest.getVersion();

      FastSparseSet<Integer> vers = varmap.get(varindex);

      int cardinality = vers != null ? vers.getCardinality() : 0;
      if (cardinality == 1) { // size == 1
        if (current_vers != 0) {
          if (calcLiveVars) {
            varMapToGraph(new VarVersionPair(varindex, current_vers), varmap);
          }

          setCurrentVar(varmap, varindex, current_vers);
        } else {
          // split last version
          int usever = getNextFreeVersion(varindex, stat);

          // set version
          vardest.setVersion(usever);
          setCurrentVar(varmap, varindex, usever);

          // ssu graph
          int lastver = vers.iterator().next();
          VarVersionNode prenode = ssuversions.nodes.getWithKey(new VarVersionPair(varindex, lastver));
          VarVersionNode usenode = ssuversions.createNode(new VarVersionPair(varindex, usever));
          VarVersionEdge edge = new VarVersionEdge(VarVersionEdge.EDGE_GENERAL, prenode, usenode);
          prenode.addSuccessor(edge);
          usenode.addPredecessor(edge);
        }
      } else if (cardinality == 2) { // size > 1

        if (current_vers != 0) {
          if (calcLiveVars) {
            varMapToGraph(new VarVersionPair(varindex, current_vers), varmap);
          }
          setCurrentVar(varmap, varindex, current_vers);
        }
        else {
          // split version
          int usever = getNextFreeVersion(varindex, stat);
          // set version
          vardest.setVersion(usever);

          // ssu node
          ssuversions.createNode(new VarVersionPair(varindex, usever));

          setCurrentVar(varmap, varindex, usever);

          current_vers = usever;
        }

        createOrUpdatePhiNode(new VarVersionPair(varindex, current_vers), vers, stat);
      } // vers.size() == 0 means uninitialized variable
      else if (cardinality == 0) {
        if (current_vers != 0) {
          if (calcLiveVars) {
            varMapToGraph(new VarVersionPair(varindex, vardest.getVersion()), varmap);
          }
          setCurrentVar(varmap, varindex, vardest.getVersion());
        }
        else {
          int usever = getNextFreeVersion(varindex, stat);
          vardest.setVersion(usever);
          ssuversions.createNode(new VarVersionPair(varindex, usever), vardest.getLVT());
          setCurrentVar(varmap, varindex, usever);
        }
      }
    }
  }

  private void createOrUpdatePhiNode(VarVersionPair phivar, FastSparseSet<Integer> vers, Statement stat) {

//    FastSparseSet<Integer> versCopy = vers.getCopy();
    Set<Integer> removed = new HashSet<>();
//    HashSet<Integer> phiVers = new HashSet<>();

    // take into account the corresponding mm/pp node if existing
    int ppvers = phantomppnodes.containsKey(phivar) ? phantomppnodes.get(phivar).version : -1;

    // ssu graph
    VarVersionNode phinode = ssuversions.nodes.getWithKey(phivar);
    List<VarVersionEdge> lstPreds = new ArrayList<>(phinode.preds);
    if (lstPreds.size() == 1) {
      // not yet a phi node
      VarVersionEdge edge = lstPreds.get(0);
      edge.source.removeSuccessor(edge);
      phinode.removePredecessor(edge);
    }
    else {
      for (VarVersionEdge edge : lstPreds) {
        int verssrc = edge.source.preds.iterator().next().source.version;
        if (!vers.contains(verssrc) && verssrc != ppvers) {
          edge.source.removeSuccessor(edge);
          phinode.removePredecessor(edge);
        }
        else {
//          versCopy.remove(verssrc);
          removed.add(verssrc);
//          phiVers.add(verssrc);
        }
      }
    }

    List<VarVersionNode> colnodes = new ArrayList<>();
    List<VarVersionPair> colpaars = new ArrayList<>();

//    for (int ver : versCopy) {
    for (int ver : vers) {
      if (removed.contains(ver)) {
        continue;
      }

      VarVersionNode prenode = ssuversions.nodes.getWithKey(new VarVersionPair(phivar.var, ver));

      int tempver = getNextFreeVersion(phivar.var, stat);

      VarVersionNode tempnode = new VarVersionNode(phivar.var, tempver);

      colnodes.add(tempnode);
      colpaars.add(new VarVersionPair(phivar.var, (int) tempver));

      VarVersionEdge edge = new VarVersionEdge(VarVersionEdge.EDGE_GENERAL, prenode, tempnode);

      prenode.addSuccessor(edge);
      tempnode.addPredecessor(edge);


      edge = new VarVersionEdge(VarVersionEdge.EDGE_GENERAL, tempnode, phinode);
      tempnode.addSuccessor(edge);
      phinode.addPredecessor(edge);

//      phiVers.add(tempver);
    }

    ssuversions.addNodes(colnodes, colpaars);
  }

  private void varMapToGraph(VarVersionPair varpaar, SFormsFastMapDirect varmap) {

    VBStyleCollection<VarVersionNode, VarVersionPair> nodes = ssuversions.nodes;

    VarVersionNode node = nodes.getWithKey(varpaar);

//    node.live = new SFormsFastMapDirect(varmap);
    node.live = varmap.getCopy();
  }

  // Gets the next version to assign to a variable
  private int getNextFreeVersion(int var, Statement stat) {
    Integer nextver = lastversion.get(var);

    if (nextver == null) {
      nextver = 1;
    } else {
      nextver++;
    }

    lastversion.put(var, nextver);

    // save the first protected range, containing current statement
    if (stat != null) { // null iff phantom version
      Integer firstRangeId = getFirstProtectedRange(stat);

      if (firstRangeId != null) {
        mapVersionFirstRange.put(new VarVersionPair(var, (int) nextver), firstRangeId);
      }
    }

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

    boolean isFinallyExit = dgraph.mapShortRangeFinallyPaths.containsKey(predid);

    if (nodeid.equals(dgraph.mapNegIfBranch.get(predid))) {
      if (outNegVarVersions.containsKey(predid)) {
        mapNew = outNegVarVersions.get(predid).getCopy();
      }
    }
    else if (outVarVersions.containsKey(predid)) {
      mapNew = outVarVersions.get(predid).getCopy();
    }

    if (isFinallyExit) {

      SFormsFastMapDirect mapNewTemp = mapNew.getCopy();

      SFormsFastMapDirect mapTrueSource = new SFormsFastMapDirect();

      String exceptionDest = dgraph.mapFinallyMonitorExceptionPathExits.get(predid);
      boolean isExceptionMonitorExit = (exceptionDest != null && !nodeid.equals(exceptionDest));

      HashSet<String> setLongPathWrapper = new HashSet<>();
      for (List<FinallyPathWrapper> lstwrapper : dgraph.mapLongRangeFinallyPaths.values()) {
        for (FinallyPathWrapper finwraplong : lstwrapper) {
          setLongPathWrapper.add(finwraplong.destination + "##" + finwraplong.source);
        }
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
        mapNew.intersection(mapNewTemp);

        if (!mapTrueSource.isEmpty() && !mapNew.isEmpty()) { // FIXME: what for??

          // replace phi versions with corresponding phantom ones
          HashMap<VarVersionPair, VarVersionPair> mapPhantom = phantomexitnodes.get(predid);
          if (mapPhantom == null) {
            mapPhantom = new HashMap<>();
          }

          SFormsFastMapDirect mapExitVar = mapNew.getCopy();
          mapExitVar.complement(mapTrueSource);

          for (Entry<Integer, FastSparseSet<Integer>> ent : mapExitVar.entryList()) {
            for (int version : ent.getValue()) {

              int varindex = ent.getKey();
              VarVersionPair exitvar = new VarVersionPair(varindex, version);
              FastSparseSet<Integer> newSet = mapNew.get(varindex);

              // remove the actual exit version
              newSet.remove(version);

              // get or create phantom version
              VarVersionPair phantomvar = mapPhantom.get(exitvar);
              if (phantomvar == null) {
                int newversion = getNextFreeVersion(exitvar.var, null);
                phantomvar = new VarVersionPair(exitvar.var, newversion);

                VarVersionNode exitnode = ssuversions.nodes.getWithKey(exitvar);
                VarVersionNode phantomnode = ssuversions.createNode(phantomvar);
                phantomnode.flags |= VarVersionNode.FLAG_PHANTOM_FINEXIT;

                VarVersionEdge edge = new VarVersionEdge(VarVersionEdge.EDGE_PHANTOM, exitnode, phantomnode);
                exitnode.addSuccessor(edge);
                phantomnode.addPredecessor(edge);

                mapPhantom.put(exitvar, phantomvar);
              }

              // add phantom version
              newSet.add(phantomvar.version);
            }
          }

          if (!mapPhantom.isEmpty()) {
            phantomexitnodes.put(predid, mapPhantom);
          }
        }
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
          int version = getNextFreeVersion(varindex, stat); // == 1

          map = new SFormsFastMapDirect();
          setCurrentVar(map, varindex, version);

          extraVarVersions.put(dgraph.nodes.getWithKey(flatthelper.getMapDestinationNodes().get(stat.getStats().get(i).id)[0]).id, map);
          //ssuversions.createOrGetNode(new VarVersionPair(varindex, version));
          ssuversions.createNode(new VarVersionPair(varindex, version));
        }
    }

    for (Statement st : stat.getStats()) {
      setCatchMaps(st, dgraph, flatthelper);
    }
  }

  private SFormsFastMapDirect createFirstMap(StructMethod mt, RootStatement root) {
    boolean thisvar = !mt.hasModifier(CodeConstants.ACC_STATIC);

    MethodDescriptor md = MethodDescriptor.parseDescriptor(mt.getDescriptor());

    int paramcount = md.params.length + (thisvar ? 1 : 0);

    int varindex = 0;
    SFormsFastMapDirect map = new SFormsFastMapDirect();
    for (int i = 0; i < paramcount; i++) {
      int version = getNextFreeVersion(varindex, root); // == 1

      FastSparseSet<Integer> set = factory.spawnEmptySet();
      set.add(version);
      map.put(varindex, set);
      ssuversions.createNode(new VarVersionPair(varindex, version));

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

  private static Integer getFirstProtectedRange(Statement stat) {

    while (true) {
      Statement parent = stat.getParent();

      if (parent == null) {
        break;
      }

      if (parent.type == Statement.TYPE_CATCHALL ||
          parent.type == Statement.TYPE_TRYCATCH) {
        if (parent.getFirst() == stat) {
          return parent.id;
        }
      }
      else if (parent.type == Statement.TYPE_SYNCRONIZED) {
        if (((SynchronizedStatement)parent).getBody() == stat) {
          return parent.id;
        }
      }

      stat = parent;
    }

    return null;
  }

  public VarVersionsGraph getSsuVersions() {
    return ssuversions;
  }

  public SFormsFastMapDirect getLiveVarVersionsMap(VarVersionPair varpaar) {
    VarVersionNode node = ssuversions.nodes.getWithKey(varpaar);
    if (node != null) {
      return node.live == null ? new SFormsFastMapDirect() : node.live;
    }

    return null;
  }

  public HashMap<VarVersionPair, Integer> getMapVersionFirstRange() {
    return mapVersionFirstRange;
  }

  public HashMap<Integer, Integer> getMapFieldVars() {
    return mapFieldVars;
  }

  public HashMap<VarVersionPair, VarVersionPair> getVarAssignmentMap() {
    return varAssignmentMap;
  }
}
