// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.modules.decompiler.flow;

import org.jetbrains.java.decompiler.modules.decompiler.StatEdge;
import org.jetbrains.java.decompiler.modules.decompiler.ValidationHelper;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.stats.*;
import org.jetbrains.java.decompiler.util.DotExporter;
import org.jetbrains.java.decompiler.util.ListStack;
import org.jetbrains.java.decompiler.util.VBStyleCollection;

import java.util.*;
import java.util.Map.Entry;


public class FlattenStatementsHelper {
  // statement.id, node.id(direct), node.id(continue)
  private final Map<Integer, String[]> mapDestinationNodes = new HashMap<>();

  // Edge.Type.ordinal => statement to direct node map
  @SuppressWarnings("unchecked")
  private final Map<Statement, DirectNode>[] mapDestinationNodes2 = new Map[]{new HashMap<>(), new HashMap<>(), new HashMap<>()};

  // node.id(source), statement.id(destination), edge type
  private final List<Edge> continueEdges = new ArrayList<>();

  // node.id(exit), [node.id(source), statement.id(destination)]
  private final Map<String, List<String[]>> mapShortRangeFinallyPathIds = new HashMap<>();

  // node.id(exit), [node.id(source), statement.id(destination)]
  private final Map<String, List<String[]>> mapLongRangeFinallyPathIds = new HashMap<>();

  // positive if branches
  private final Map<String, Integer> mapPosIfBranch = new HashMap<>();

  private final ListStack<List<DirectNode>> tryNodesStack = new ListStack<>();

  private DirectGraph graph;

  private RootStatement root;

  public DirectGraph buildDirectGraph(RootStatement root) {

    this.root = root;

    this.graph = new DirectGraph();

    this.graph.first = flattenStatement();

    // dummy exit node
    Statement dummyexit = root.getDummyExit();
    DirectNode node = this.createDirectNode(dummyexit);
    this.addDestination(dummyexit, node);

    setEdges();

    graph.sortReversePostOrder();

    graph.mapDestinationNodes.putAll(mapDestinationNodes);

    return graph;
  }

  private void addDestination(Statement stat, DirectNode node) {
    this.addDestination(stat, node, Edge.Type.REGULAR);
  }

  private void addDestination(Statement stat, DirectNode node, Edge.Type type) {
    this.mapDestinationNodes2[type.ordinal()].put(stat, node);
    switch (type) {
      case REGULAR:
        this.mapDestinationNodes.put(stat.id, new String[]{node.id, null});
        break;
      case CONTINUE:
        this.mapDestinationNodes.get(stat.id)[1] = node.id;
        break;
      case ALTERNATIVE:
        this.mapDestinationNodes.put(-stat.id, new String[]{node.id, null});
        break;
      default:
        throw new RuntimeException("Unexpected edge type: " + type);
    }
  }

  private DirectNode createDirectNode(Statement stat) {
    final DirectNode directNode = this.createDirectNode(stat, DirectNodeType.DIRECT);
    if (stat instanceof BasicBlockStatement) {
      directNode.block = (BasicBlockStatement) stat;
    }

    return directNode;
  }

  private DirectNode createDirectNode(Statement stat, DirectNodeType type) {
    DirectNode node = DirectNode.forStat(type, stat);
    this.graph.nodes.addWithKey(node, node.id);

    if (!this.tryNodesStack.isEmpty()) {
      this.tryNodesStack.peek().add(node);
      // the try itself will put all nodes in the next try too
    }

    return node;
  }

  private DirectNode flattenStatement() {
    return this.flattenStatement(this.root, new ListStack<>());
  }

  private DirectNode flattenStatement(
    Statement stat,
    ListStack<StackEntry> stackFinally
  ) {
    List<StatEdge> lstSuccEdges = new ArrayList<>();
    DirectNode sourceNode = null;
    DirectNode destinationNode = null;

    {
      switch (stat.type) {
        case BASIC_BLOCK: {
          DirectNode node = this.createDirectNode(stat);
          this.addDestination(stat, node);
          destinationNode = node;
          if (stat.getExprents() != null) {
            node.exprents = stat.getExprents();
          }

          lstSuccEdges.addAll(stat.getSuccessorEdges(Statement.STATEDGE_DIRECT_ALL));
          sourceNode = node;

          // 'if' statement: record positive branch
          if (stat.getLastBasicType() == Statement.LastBasicType.IF) {
            if (lstSuccEdges.isEmpty()) {
              throw new IllegalStateException("Empty successor list for node " + sourceNode.id);
            }

            mapPosIfBranch.put(sourceNode.id, lstSuccEdges.get(0).getDestination().id);
          }

          List<StatEdge> basicPreds = stat.getAllPredecessorEdges();

          // TODO: sourcenode instead of stat.id?
          if (basicPreds.size() == 1) {
            StatEdge predEdge = basicPreds.get(0);

            // Look if this basic block is the successor of a sequence,
            // and connect the sequence to the block if so
            // TODO: should this be done in the sequence handling instead?
            // TODO: what is this for?
            // TODO: why is it adding ad edge from the start of the sequence to the block?
            // TODO: why is it using the basic head id, instead of the sequence id?

            if (predEdge.getType() == StatEdge.TYPE_REGULAR) {
              if (predEdge.getSource() instanceof SequenceStatement) {
                addEdgeIfPossible(predEdge.getSource().getBasichead().id, stat);
              }
            }
          }
          break;
        }
        case CATCH_ALL:
        case TRY_CATCH: { // TODO: should these 2 be merged into 1 class?
          DirectNode node = this.createDirectNode(stat, DirectNodeType.TRY);
          this.addDestination(stat, node);
          destinationNode = node;

          boolean isFinally = stat instanceof CatchAllStatement && ((CatchAllStatement) stat).isFinally();

          int endCatchIndex = isFinally ? stat.getStats().size() - 1 : stat.getStats().size();

          if (stat instanceof CatchStatement) {
            CatchStatement catchStat = (CatchStatement) stat;
            List<Exprent> resources = catchStat.getResources();
            if (!resources.isEmpty()) {
              node.exprents = resources;
            }
          }


          if (isFinally) {
            stackFinally.push(new StackEntry((CatchAllStatement) stat, false));
          }

          List<DirectNode> tryNodes = new ArrayList<>();
          this.tryNodesStack.add(tryNodes);

          DirectNode tryBlock = this.flattenStatement(stat.getFirst(), stackFinally);
          node.addSuccessor(DirectEdge.of(node, tryBlock));

          ValidationHelper.assertTrue(tryNodes == this.tryNodesStack.pop(), "tryNodesStack is broken");
          if (!this.tryNodesStack.isEmpty()) {
            this.tryNodesStack.peek().addAll(tryNodes);
          }

          // do catch and finally blocks
          VBStyleCollection<Statement, Integer> stats = stat.getStats();
          for (int i = 1; i < stats.size(); i++) {
            Statement st = stats.get(i);

            if (isFinally) {
              stackFinally.pop();
              stackFinally.push(new StackEntry((CatchAllStatement) stat, true, StatEdge.TYPE_BREAK,
                root.getDummyExit(), st, st, node, node, true));

            }

            DirectNode handlerNode = this.flattenStatement(st, stackFinally);

            // TODO: should this be an exception edge for catch blocks?
            node.addSuccessor(DirectEdge.of(node, handlerNode));

            if (i == endCatchIndex) {
              stackFinally.pop();
              // finally
              // TODO: finally handling
            } else {
              for (DirectNode innerTryNode : tryNodes) {
                innerTryNode.addSuccessor(DirectEdge.exception(innerTryNode, handlerNode));
              }
            }
          }
          break;
        }
        case DO: {
          if (!stat.hasBasicSuccEdge()) { // infinite loop TODO: why no just check the loop type?
            if (stat.hasSuccessor(StatEdge.TYPE_REGULAR)) {
              Statement dest = stat.getSuccessorEdges(StatEdge.TYPE_REGULAR).get(0).getDestination();

              if (dest.getAllPredecessorEdges().size() == 1) {
                // If the successor only has one backedge, it is the current loop
                List<StatEdge> prededges = stat.getPredecessorEdges(StatEdge.TYPE_REGULAR);

                if (!prededges.isEmpty()) {
                  StatEdge prededge = prededges.get(0);

                  // Find destinations of loop's predecessor

                  addEdgeIfPossible(prededge.getSource().id, dest);

                  // Note: It seems that for infinite loops, the loop's predecessor gets an extra edge
                  // to the loop's "destination" (usually the place the loop breaks to).
                  // TODO: This feels wrong. EDIT: seems to try to "fix"
                  //       a finally processing bug, consider this a temporary
                  //       bandaid
                }
              }
            }
          }

          DirectNode body = this.flattenStatement(stat.getFirst(), stackFinally);

          DoStatement dostat = (DoStatement) stat;
          DoStatement.Type looptype = dostat.getLooptype();

          if (looptype == DoStatement.Type.INFINITE) {
            this.addDestination(stat, body);
            destinationNode = body;
            this.addDestination(stat, body, Edge.Type.CONTINUE);
            break;
          }

          lstSuccEdges.add(stat.getFirstSuccessor());  // exactly one edge

          switch (looptype) {
            case WHILE:
            case DO_WHILE: {
              DirectNode conditionNode = this.createDirectNode(stat, DirectNodeType.CONDITION);
              conditionNode.exprents = dostat.getConditionExprentList();

              conditionNode.addSuccessor(DirectEdge.of(conditionNode, body));

              if (looptype == DoStatement.Type.WHILE) {
                this.addDestination(stat, conditionNode); // for a while, the start is the condition
                destinationNode = conditionNode;
                this.addDestination(stat, conditionNode, Edge.Type.CONTINUE);
              } else {
                this.addDestination(stat, body); // for a do-while, the start is the body
                destinationNode = body;
                this.addDestination(stat, conditionNode, Edge.Type.CONTINUE);

                boolean found = false;
                for (Edge edge : continueEdges) {
                  if (edge.statid.equals(stat.id) && edge.edgetype == StatEdge.TYPE_CONTINUE) {
                    found = true;
                    break;
                  }
                }
                if (!found) {
                  // if no continue edge was found, add one from the body
                  // TODO: isn't there a better way? also, why is this only an issue for some of the while types?

                  // listEdge target: known (node)
                  continueEdges.add(new Edge(body, stat, Edge.Type.CONTINUE));
                }
              }
              sourceNode = conditionNode;
              break;
            }
            case FOR: {
              DirectNode initNode = this.createDirectNode(stat, DirectNodeType.INIT);
              if (dostat.getInitExprent() != null) {
                initNode.exprents = dostat.getInitExprentList();
              }

              DirectNode conditionNode = this.createDirectNode(stat, DirectNodeType.CONDITION);
              conditionNode.exprents = dostat.getConditionExprentList();

              DirectNode incrementNode = this.createDirectNode(stat, DirectNodeType.INCREMENT);
              incrementNode.exprents = dostat.getIncExprentList();

              this.addDestination(stat, initNode); // for a for, the start is the init
              destinationNode = initNode;
              this.addDestination(stat, incrementNode, Edge.Type.CONTINUE); // target for all continue edges

              conditionNode.addSuccessor(DirectEdge.of(conditionNode, body));
              initNode.addSuccessor(DirectEdge.of(initNode, conditionNode));
              incrementNode.addSuccessor(DirectEdge.of(incrementNode, conditionNode));

              boolean found = false;
              for (Edge edge : continueEdges) {
                if (edge.statid.equals(stat.id) && edge.edgetype == StatEdge.TYPE_CONTINUE) {
                  found = true;
                  break;
                }
              }

              if (!found) {
                // if no continue edge was found, add one from the body
                // TODO: isn't there a better way?

                // listEdge target: known (incNode)
                this.continueEdges.add(new Edge(body, stat, Edge.Type.CONTINUE));
              }

              sourceNode = conditionNode;
              break;
            }
            case FOR_EACH: {
              // for (init : inc)
              //
              // is essentially
              //
              // for (inc; ; init)
              // TODO: that ordering does not make sense

              DirectNode inc = this.createDirectNode(stat, DirectNodeType.INCREMENT);
              inc.exprents = dostat.getIncExprentList();

              // Init is foreach variable definition
              DirectNode init = this.createDirectNode(stat, DirectNodeType.FOREACH_VARDEF);
              init.exprents = dostat.getInitExprentList();

              this.addDestination(stat, inc);
              destinationNode = inc;
              this.addDestination(stat, init, Edge.Type.CONTINUE); // target for all continue edges

              init.addSuccessor(DirectEdge.of(init, body));
              inc.addSuccessor(DirectEdge.of(inc, init));

              boolean found = false;
              for (Edge edge : continueEdges) {
                if (edge.statid.equals(stat.id) && edge.edgetype == StatEdge.TYPE_CONTINUE) {
                  found = true;
                  break;
                }
              }

              if (!found) {
                // if no continue edge was found, add one from the body
                // TODO: isn't there a better way?

                // listEdge target: known (init)
                this.continueEdges.add(new Edge(body, stat, Edge.Type.CONTINUE));
              }

              sourceNode = init;
              break;
            }
          }
          break;
        }
        case SYNCHRONIZED:
        case SWITCH:
        case IF: {
          int statsize = stat.getStats().size();
          if (stat instanceof SynchronizedStatement) {
            statsize = 2;  // exclude the handler if synchronized
          }

          List<Exprent> tailexprlst;

          switch (stat.type) {
            case SYNCHRONIZED:
              tailexprlst = ((SynchronizedStatement) stat).getHeadexprentList();
              break;
            case SWITCH:
              tailexprlst = ((SwitchStatement) stat).getHeadexprentList();
              break;
            case IF:
              tailexprlst = ((IfStatement) stat).getHeadexprentList();
              break;
            default:
              throw new RuntimeException("Unexpected statement type: " + stat.type);
          }

          Statement first = stat.getFirst();
          DirectNode firstNode = this.createDirectNode(first);
          DirectNode outNode = firstNode;
          this.addDestination(first, firstNode);
          if (first.getExprents() != null) {
            firstNode.exprents = first.getExprents();
          }

          List<StatEdge> edges = first.getSuccessorEdges(Statement.STATEDGE_DIRECT_ALL);

          if (tailexprlst != null && tailexprlst.get(0) != null) {
            DirectNode tail = this.createDirectNode(stat, DirectNodeType.TAIL);
            tail.exprents = tailexprlst;

            firstNode.addSuccessor(DirectEdge.of(firstNode, tail));

            outNode = tail;
          }

          // 'if' statement: record positive branch
          if (stat instanceof IfStatement) {
            mapPosIfBranch.put(outNode.id, ((IfStatement) stat).getIfEdge().getDestination().id);
          }

          {
            List<StatEdge> basicPreds = stat.getAllPredecessorEdges();

            // TODO: sourcenode instead of stat.id?
            if (basicPreds.size() == 1) {
              StatEdge predEdge = basicPreds.get(0);

              // Look if this basic block is the successor of a sequence,
              // and connect the sequence to the block if so
              // TODO: should this be done in the sequence handling instead?
              // TODO: what is this for?
              // TODO: why is it adding ad edge from the start of the sequence to the block?
              // TODO: why is it using the basic head id, instead of the sequence id?

              if (predEdge.getType() == StatEdge.TYPE_REGULAR) {
                if (predEdge.getSource() instanceof SequenceStatement) {
                  addEdgeIfPossible(predEdge.getSource().getBasichead().id, stat);
                }
              }
            }
          }


          for (int i = 1; i < statsize; i++) {
            this.flattenStatement(stat.getStats().get(i), stackFinally);
          }

          this.addDestination(stat, firstNode);
          destinationNode = firstNode;

          // Try to intercept the edges leaving the switch head and replace with relevant case nodes
          if (stat instanceof SwitchStatement) {
            SwitchStatement switchSt = (SwitchStatement) stat;

            List<List<StatEdge>> caseEdges = switchSt.getCaseEdges();
            List<List<Exprent>> caseValues = switchSt.getCaseValues();
            List<Statement> caseStatements = switchSt.getCaseStatements();

            if (caseEdges.size() != caseValues.size()) {
              if (caseEdges.size() + 1 != caseValues.size()
                  || caseValues.get(caseValues.size() - 1).size() != 1
                  || caseValues.get(caseValues.size() - 1).get(0) != null) {
                throw new RuntimeException("Case edges and case values do not match");
              }
            }

            for (int i = 0; i < caseEdges.size(); i++) {

              List<Exprent> values = caseValues.get(i);
              List<StatEdge> thisCaseEdges = caseEdges.get(i);
              Statement thisCaseStatement = caseStatements.get(i);

              // Default case val can be null
              List<Exprent> finalVals = null;
              if (values != null) {
                finalVals = new ArrayList<>();
                for (Exprent value : values) {
                  if (value != null) {
                    finalVals.add(value);
                  }
                }
              }

              // Build node out of the case exprents
              DirectNode caseNode = this.createDirectNode(thisCaseStatement, DirectNodeType.CASE);
              caseNode.exprents = finalVals;

              outNode.addSuccessor(DirectEdge.of(outNode, caseNode));

              this.handleFinally(stackFinally, thisCaseEdges, caseNode);
            }

            if (caseEdges.size() < caseValues.size()) {
              // default case
              List<StatEdge> thisCaseEdges = new ArrayList<>(1);
              thisCaseEdges.add(switchSt.getDefaultEdge());

              this.handleFinally(stackFinally, thisCaseEdges, outNode);
            }
          } else {
            // add edges
            this.handleFinally(stackFinally, edges, outNode);
          }

          if (stat instanceof IfStatement && ((IfStatement) stat).iftype == IfStatement.IFTYPE_IF && !stat.getAllSuccessorEdges().isEmpty()) {
            lstSuccEdges.add(stat.getSuccessorEdges(Statement.STATEDGE_DIRECT_ALL).get(0));  // exactly one edge
            sourceNode = outNode;
          }

          // Adds an edge from the last if statement to the current if statement, if the current if statement's head statement has no predecessor
          // This was made to mask a failure in EliminateLoopsHelper and isn't used currently (over the current test set) but could theoretically still happen!
          // TODO: what?
          // TODO: use java code gen to generate a test for this?
          if (stat instanceof IfStatement && ((IfStatement) stat).iftype == IfStatement.IFTYPE_IF && !stat.getPredecessorEdges(StatEdge.TYPE_REGULAR).isEmpty()) {
            if (stat.getFirst().getPredecessorEdges(StatEdge.TYPE_REGULAR).isEmpty()) {
              StatEdge edge = stat.getPredecessorEdges(StatEdge.TYPE_REGULAR).get(0);

              Statement source = edge.getSource();
              if (source instanceof IfStatement && ((IfStatement) source).iftype == IfStatement.IFTYPE_IF && !source.getAllSuccessorEdges().isEmpty()) {
                DirectNode srcnd = graph.nodes.getWithKey(source.getFirst().id + "_tail");

                if (srcnd != null) {
                  // old ifstat->head
                  Edge newEdge = new Edge(srcnd, stat, edge.getType() == StatEdge.TYPE_CONTINUE ? Edge.Type.CONTINUE : Edge.Type.REGULAR);

                  // Add if it doesn't exist already
                  if (!this.continueEdges.contains(newEdge)) {
                    this.continueEdges.add(newEdge);
                  }
                }
              }
            }
          }
          break;
        }
        case SEQUENCE:
        case ROOT: {
          int statsize = stat.getStats().size();

          DirectNode firstBlock = this.flattenStatement(stat.getFirst(), stackFinally);

          for (int i = 1; i < statsize; i++) {
            this.flattenStatement(stat.getStats().get(i), stackFinally);
          }

          this.addDestination(stat, firstBlock);
          destinationNode = firstBlock;

          break;
        }
      }
    }

    if (sourceNode != null) {
      this.handleFinally(stackFinally, lstSuccEdges, sourceNode);
    }

    return destinationNode;
  }

  private void handleFinally(List<StackEntry> stackFinally, List<StatEdge> lstSuccEdges, DirectNode sourcenode) {
    for (StatEdge edge : lstSuccEdges) {
      LinkedList<StackEntry> stack = new LinkedList<>(stackFinally);

      int edgetype = edge.getType();
      Statement destination = edge.getDestination();

      DirectNode finallyShortRangeSource = sourcenode;
      DirectNode finallyLongRangeSource = sourcenode;
      Statement finallyShortRangeEntry = null;
      Statement finallyLongRangeEntry = null;

      boolean isFinallyMonitorExceptionPath = false;

      boolean isFinallyExit = false;

      while (true) {

        StackEntry entry = null;
        if (!stack.isEmpty()) {
          entry = stack.getLast();
        }

        boolean created = true;

        if (entry == null) {
          saveEdge(sourcenode, destination, edgetype, isFinallyExit ? finallyShortRangeSource : null, finallyLongRangeSource,
            finallyShortRangeEntry, finallyLongRangeEntry, isFinallyMonitorExceptionPath);
        } else {
          CatchAllStatement catchall = entry.catchstatement;

          if (entry.state) { // finally handler statement
            if (edgetype == StatEdge.TYPE_FINALLYEXIT) {

              stack.removeLast();
              destination = entry.destination;
              edgetype = entry.edgetype;

              finallyShortRangeSource = entry.finallyShortRangeSource;
              finallyLongRangeSource = entry.finallyLongRangeSource;
              finallyShortRangeEntry = entry.finallyShortRangeEntry;
              finallyLongRangeEntry = entry.finallyLongRangeEntry;

              isFinallyExit = true;
              isFinallyMonitorExceptionPath = (catchall.getMonitor() != null) & entry.isFinallyExceptionPath;

              created = false;
            } else {
              if (!catchall.containsStatementStrict(destination)) {
                stack.removeLast();
                created = false;
              } else {
                saveEdge(sourcenode, destination, edgetype, isFinallyExit ? finallyShortRangeSource : null, finallyLongRangeSource,
                  finallyShortRangeEntry, finallyLongRangeEntry, isFinallyMonitorExceptionPath);
              }
            }
          } else { // finally protected try statement
            if (!catchall.containsStatementStrict(destination)) {

              // FIXME: this is a hack, the edges need to be more properly defined from the finally handler to it's destination
              //  Otherwise problems can occur where variable usage scopes aren't correct!
              // Edge from finally handler head to destination
              continueEdges.add(new Edge(sourcenode.id, destination.id, edgetype));

              saveEdge(sourcenode, catchall.getHandler(), StatEdge.TYPE_REGULAR, isFinallyExit ? finallyShortRangeSource : null,
                finallyLongRangeSource, finallyShortRangeEntry, finallyLongRangeEntry, isFinallyMonitorExceptionPath);

              stack.removeLast();
              stack.add(new StackEntry(catchall, true, edgetype, destination, catchall.getHandler(),
                finallyLongRangeEntry == null ? catchall.getHandler() : finallyLongRangeEntry,
                sourcenode, finallyLongRangeSource, false));

              this.flattenStatement(catchall.getHandler(), new ListStack<>(stack));

              return;
            } else {
              saveEdge(sourcenode, destination, edgetype, isFinallyExit ? finallyShortRangeSource : null, finallyLongRangeSource,
                finallyShortRangeEntry, finallyLongRangeEntry, isFinallyMonitorExceptionPath);
            }
          }
        }

        if (created) {
          break;
        }
      }
    }
  }

  private void addEdgeIfPossible(Integer predEdge, Statement stat) {
    String[] lastbasicdests = mapDestinationNodes.get(predEdge);

    if (lastbasicdests != null) {
      continueEdges.add(new Edge(graph.nodes.getWithKey(lastbasicdests[0]).id, stat.id, StatEdge.TYPE_REGULAR));
    }
  }

  private void saveEdge(DirectNode sourcenode,
                        Statement destination,
                        int edgetype,
                        DirectNode finallyShortRangeSource,
                        DirectNode finallyLongRangeSource,
                        Statement finallyShortRangeEntry,
                        Statement finallyLongRangeEntry,
                        boolean isFinallyMonitorExceptionPath) {

    if (edgetype != StatEdge.TYPE_FINALLYEXIT) {
      continueEdges.add(new Edge(sourcenode.id, destination.id, edgetype));
    }

    if (finallyShortRangeSource != null) {
      boolean isContinueEdge = (edgetype == StatEdge.TYPE_CONTINUE);

      mapShortRangeFinallyPathIds.computeIfAbsent(sourcenode.id, k -> new ArrayList<>()).add(new String[]{
        finallyShortRangeSource.id,
        String.valueOf(destination.id),
        String.valueOf(finallyShortRangeEntry.id),
        isFinallyMonitorExceptionPath ? "1" : null,
        isContinueEdge ? "1" : null});

      mapLongRangeFinallyPathIds.computeIfAbsent(sourcenode.id, k -> new ArrayList<>()).add(new String[]{
        finallyLongRangeSource.id,
        String.valueOf(destination.id),
        String.valueOf(finallyLongRangeEntry.id),
        isContinueEdge ? "1" : null});
    }
  }

  private void setEdges() {

    for (Edge edge : continueEdges) {

      String sourceid = edge.sourceid;
      Integer statid = edge.statid;

      DirectNode source = graph.nodes.getWithKey(sourceid);

      String[] strings = mapDestinationNodes.get(statid);
      if (strings == null) {
        DotExporter.toDotFile(graph, root.mt, "errorDGraph");

        throw new IllegalStateException("Could not find destination nodes for stat id " + statid + " from source " + sourceid);
      }
      // TODO: continue edge type?
      DirectNode dest = graph.nodes.getWithKey(strings[edge.edgetype == StatEdge.TYPE_CONTINUE ? 1 : 0]);

      DirectEdge diedge = edge.edgetype == StatEdge.TYPE_EXCEPTION
        ? DirectEdge.exception(source, dest)
        : DirectEdge.of(source, dest);

      source.addSuccessor(diedge);

      if (mapPosIfBranch.containsKey(sourceid) && !statid.equals(mapPosIfBranch.get(sourceid))) {
        graph.mapNegIfBranch.put(sourceid, dest.id);
      }
    }

    for (int i = 0; i < 2; i++) {
      for (Entry<String, List<String[]>> ent : (i == 0 ? mapShortRangeFinallyPathIds : mapLongRangeFinallyPathIds).entrySet()) {

        List<FinallyPathWrapper> newLst = new ArrayList<>();

        List<String[]> lst = ent.getValue();
        for (String[] arr : lst) {

          boolean isContinueEdge = arr[i == 0 ? 4 : 3] != null;

          DirectNode dest = graph.nodes.getWithKey(mapDestinationNodes.get(Integer.parseInt(arr[1]))[isContinueEdge ? 1 : 0]);
          DirectNode enter = graph.nodes.getWithKey(mapDestinationNodes.get(Integer.parseInt(arr[2]))[0]);

          newLst.add(new FinallyPathWrapper(arr[0], dest.id, enter.id));

          if (i == 0 && arr[3] != null) {
            graph.mapFinallyMonitorExceptionPathExits.put(ent.getKey(), dest.id);
          }
        }

        if (!newLst.isEmpty()) {
          (i == 0 ? graph.mapShortRangeFinallyPaths : graph.mapLongRangeFinallyPaths).put(ent.getKey(),
            new ArrayList<>(
              new HashSet<>(newLst)));
        }
      }
    }
  }

  public Map<Integer, String[]> getMapDestinationNodes() {
    return mapDestinationNodes;
  }

  public static final class FinallyPathWrapper {
    public final String source;
    public final String destination;
    public final String entry;

    private FinallyPathWrapper(String source, String destination, String entry) {
      this.source = source;
      this.destination = destination;
      this.entry = entry;
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) return true;
      if (!(o instanceof FinallyPathWrapper)) return false;

      FinallyPathWrapper fpw = (FinallyPathWrapper) o;
      return (source + ":" + destination + ":" + entry).equals(fpw.source + ":" + fpw.destination + ":" + fpw.entry);
    }

    @Override
    public int hashCode() {
      return (source + ":" + destination + ":" + entry).hashCode();
    }

    @Override
    public String toString() {
      return source + "->(" + entry + ")->" + destination;
    }
  }


  private static class StackEntry {

    public final CatchAllStatement catchstatement;
    public final boolean state;
    public final int edgetype;
    public final boolean isFinallyExceptionPath;

    public final Statement destination;
    public final Statement finallyShortRangeEntry;
    public final Statement finallyLongRangeEntry;
    public final DirectNode finallyShortRangeSource;
    public final DirectNode finallyLongRangeSource;

    StackEntry(CatchAllStatement catchstatement,
               boolean state,
               int edgetype,
               Statement destination,
               Statement finallyShortRangeEntry,
               Statement finallyLongRangeEntry,
               DirectNode finallyShortRangeSource,
               DirectNode finallyLongRangeSource,
               boolean isFinallyExceptionPath) {

      this.catchstatement = catchstatement;
      this.state = state;
      this.edgetype = edgetype;
      this.isFinallyExceptionPath = isFinallyExceptionPath;

      this.destination = destination;
      this.finallyShortRangeEntry = finallyShortRangeEntry;
      this.finallyLongRangeEntry = finallyLongRangeEntry;
      this.finallyShortRangeSource = finallyShortRangeSource;
      this.finallyLongRangeSource = finallyLongRangeSource;
    }

    StackEntry(CatchAllStatement catchstatement, boolean state) {
      this(catchstatement, state, -1, null, null, null, null, null, false);
    }
  }

  private static class Edge {
    @Deprecated
    public final String sourceid;
    public DirectNode source;
    @Deprecated
    public final Integer statid;
    public Statement dest;
    public final int edgetype;

    @Deprecated
    Edge(String sourceid, Integer statid, int edgetype) {
      this.sourceid = sourceid;
      this.statid = statid;
      this.edgetype = edgetype;
    }

    Edge(DirectNode source, Statement dest, Type edgetype) {
      this.source = source;
      this.sourceid = source.id;
      this.dest = dest;
      switch (edgetype) {
        case REGULAR:
          this.statid = dest.id;
          this.edgetype = StatEdge.TYPE_REGULAR;
          break;
        case CONTINUE:
          this.statid = dest.id;
          this.edgetype = StatEdge.TYPE_CONTINUE;
          break;
        case ALTERNATIVE:
          this.statid = -dest.id;
          this.edgetype = StatEdge.TYPE_REGULAR;
          break;
        case EXCEPTION:
          this.statid = dest.id;
          this.edgetype = StatEdge.TYPE_EXCEPTION;
          break;
        default:
          throw new RuntimeException("Unknown edge type: " + edgetype);
      }
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }

      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      Edge edge = (Edge) o;
      return edgetype == edge.edgetype && Objects.equals(sourceid, edge.sourceid) && Objects.equals(statid, edge.statid);
    }

    @Override
    public String toString() {
      return "Source: " + sourceid + " Stat: " + statid + " Edge: " + edgetype;
    }

    @Override
    public int hashCode() {
      return Objects.hash(sourceid, statid, edgetype);
    }

    enum Type {
      REGULAR,
      CONTINUE,
      ALTERNATIVE,
      EXCEPTION
    }
  }
}
