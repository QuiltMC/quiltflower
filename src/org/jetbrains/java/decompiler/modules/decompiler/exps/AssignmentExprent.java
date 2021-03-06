/*
 * Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */
package org.jetbrains.java.decompiler.modules.decompiler.exps;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.ClassesProcessor.ClassNode;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.collectors.BytecodeMappingTracer;
import org.jetbrains.java.decompiler.main.rels.MethodWrapper;
import org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.vars.CheckTypesResult;
import org.jetbrains.java.decompiler.struct.StructField;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericClassDescriptor;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericMethodDescriptor;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericType;
import org.jetbrains.java.decompiler.util.InterpreterUtil;
import org.jetbrains.java.decompiler.util.TextBuffer;

import java.util.*;

public class AssignmentExprent extends Exprent {

  public static final int CONDITION_NONE = -1;

  private static final String[] OPERATORS = {
    " += ",   // FUNCTION_ADD
    " -= ",   // FUNCTION_SUB
    " *= ",   // FUNCTION_MUL
    " /= ",   // FUNCTION_DIV
    " &= ",   // FUNCTION_AND
    " |= ",   // FUNCTION_OR
    " ^= ",   // FUNCTION_XOR
    " %= ",   // FUNCTION_REM
    " <<= ",  // FUNCTION_SHL
    " >>= ",  // FUNCTION_SHR
    " >>>= "  // FUNCTION_USHR
  };

  private Exprent left;
  private Exprent right;
  // Condition == type of assignment, -1 is `=` 0 is `+=`, 1 is `-=`, etc.
  private int condType = CONDITION_NONE;

  public AssignmentExprent(Exprent left, Exprent right, BitSet bytecodeOffsets) {
    super(EXPRENT_ASSIGNMENT);
    this.left = left;
    this.right = right;

    addBytecodeOffsets(bytecodeOffsets);
  }

  public AssignmentExprent(Exprent left, Exprent right, int condType, BitSet bytecodeOffsets) {
    this(left, right, bytecodeOffsets);
    this.condType = condType;
  }

  @Override
  public VarType getExprType() {
    return left.getExprType();
  }

  @Override
  public VarType getInferredExprType(VarType upperBounds) {
    return left.getInferredExprType(upperBounds);
  }

  @Override
  public CheckTypesResult checkExprTypeBounds() {
    CheckTypesResult result = new CheckTypesResult();

    VarType typeLeft = left.getExprType();
    VarType typeRight = right.getExprType();

    if (typeLeft.typeFamily > typeRight.typeFamily) {
      result.addMinTypeExprent(right, VarType.getMinTypeInFamily(typeLeft.typeFamily));
    }
    else if (typeLeft.typeFamily < typeRight.typeFamily) {
      result.addMinTypeExprent(left, typeRight);
    }
    else {
      result.addMinTypeExprent(left, VarType.getCommonSupertype(typeLeft, typeRight));
    }

    return result;
  }

  @Override
  public List<Exprent> getAllExprents(List<Exprent> lst) {
    lst.add(left);
    lst.add(right);
    return lst;
  }

  @Override
  public Exprent copy() {
    return new AssignmentExprent(left.copy(), right.copy(), condType, bytecode);
  }

  @Override
  public int getPrecedence() {
    return 13;
  }

  @Override
  public TextBuffer toJava(int indent) {
    VarType leftType = left.getInferredExprType(null);
    VarType rightType = right.getInferredExprType(leftType);

    boolean fieldInClassInit = false, hiddenField = false;
    if (left.type == Exprent.EXPRENT_FIELD) { // first assignment to a final field. Field name without "this" in front of it
      FieldExprent field = (FieldExprent) left;
      ClassNode node = ((ClassNode) DecompilerContext.getProperty(DecompilerContext.CURRENT_CLASS_NODE));
      if (node != null) {
        StructField fd = node.classStruct.getField(field.getName(), field.getDescriptor().descriptorString);
        if (fd != null) {
          if (field.isStatic() && fd.hasModifier(CodeConstants.ACC_FINAL)) {
            fieldInClassInit = true;
          }
          if (node.getWrapper() != null && node.getWrapper().getHiddenMembers().contains(InterpreterUtil.makeUniqueKey(fd.getName(), fd.getDescriptor()))) {
            hiddenField = true;
          }
        }
      }
    }

    if (hiddenField) {
      return new TextBuffer();
    }

    TextBuffer buffer = new TextBuffer();

    if (fieldInClassInit) {
      buffer.append(((FieldExprent) left).getName());
    } else {
      buffer.append(left.toJava(indent));
    }

    if (right.type == EXPRENT_CONST) {
      ((ConstExprent) right).adjustConstType(leftType);
    }

    this.optimizeCastForAssign();
    TextBuffer res = right.toJava(indent);

    if (condType == CONDITION_NONE) {
      this.wrapInCast(leftType, rightType, res, right.getPrecedence());
    }

    buffer.append(condType == CONDITION_NONE ? " = " : OPERATORS[condType]).append(res);

    buffer.addStartBytecodeMapping(bytecode);

    return buffer;
  }

  // E var = (T)expr; -> E var = (E)expr;
  // when E extends T & A
  private void optimizeCastForAssign() {
    if (this.right.type != EXPRENT_FUNCTION) {
      return;
    }

    FunctionExprent func = (FunctionExprent) this.right;

    if (func.getFuncType() != FunctionExprent.FUNCTION_CAST) {
      return;
    }

    VarType leftType = this.left.getInferredExprType(null);

    if (!(leftType instanceof GenericType)) {
      return;
    }

    Exprent cast = func.getLstOperands().get(1);

    MethodWrapper method = (MethodWrapper) DecompilerContext.getProperty(DecompilerContext.CURRENT_METHOD_WRAPPER);
    if (method == null) {
      return;
    }

    StructMethod mt = method.methodStruct;
    GenericMethodDescriptor descriptor = mt.getSignature();


    if (descriptor == null || descriptor.typeParameters.isEmpty()) {
      return;
    }

    List<String> params = descriptor.typeParameters;
    int index = params.indexOf(leftType.value);
    if (index == -1) {
      return;
    }

    List<List<VarType>> bounds = descriptor.typeParameterBounds;

    List<VarType> types = bounds.get(index);

    GenericClassDescriptor classDescriptor = method.classStruct.getSignature();
    if (classDescriptor != null) {
      for (VarType type : new ArrayList<>(types)) {
        int idex = classDescriptor.fparameters.indexOf(type.value);

        if (idex != -1) {
          types.addAll(classDescriptor.fbounds.get(idex));
        }
      }
    }

    VarType rightType = cast.getInferredExprType(leftType);

    // Check if type bound includes the type that we are attempting to cast to
    for (VarType type : types) {
      if (rightType.value.equals(type.value)) {
        ((ConstExprent)cast).setConstType(leftType);
      }
    }
  }

  private void wrapInCast(VarType left, VarType right, TextBuffer buf, int precedence) {
    boolean needsCast = !left.isSuperset(right) && (right.equals(VarType.VARTYPE_OBJECT) || left.type != CodeConstants.TYPE_OBJECT);

    if (left.isGeneric() || right.isGeneric()) {
      Map<VarType, List<VarType>> names = this.getNamedGenerics();
      int arrayDim = 0;

      if (left.arrayDim == right.arrayDim && left.arrayDim > 0) {
        arrayDim = left.arrayDim;
        left = left.resizeArrayDim(0);
        right = right.resizeArrayDim(0);
      }

      List<? extends VarType> types = names.get(right);
      if (types == null) {
        types = names.get(left);
      }

      if (types != null) {
        boolean anyMatch = false; //TODO: allMatch instead of anyMatch?
        for (VarType type : types) {
          if (type.equals(VarType.VARTYPE_OBJECT) && right.equals(VarType.VARTYPE_OBJECT)) {
            continue;
          }
          anyMatch |= right.value == null /*null const doesn't need cast*/ || DecompilerContext.getStructContext().instanceOf(right.value, type.value);
        }

        if (anyMatch) {
          needsCast = false;
        }
      }

      if (arrayDim != 0) {
        left = left.resizeArrayDim(arrayDim);
      }
    }

    if (this.right.type == Exprent.EXPRENT_FUNCTION) {
      FunctionExprent func = (FunctionExprent) this.right;
      if (func.getFuncType() == FunctionExprent.FUNCTION_CAST && func.doesCast()) {
        // Don't cast if there's already a cast
        if (func.getLstOperands().get(1).getExprType().equals(left)) {
          needsCast = false;
        }
      }
    }

    if (!needsCast && ExprProcessor.doesContravarianceNeedCast(left, right)) {
      needsCast = true;
    }

    if (!needsCast) {
      return;
    }

    if (precedence >= FunctionExprent.getPrecedence(FunctionExprent.FUNCTION_CAST)) {
      buf.enclose("(", ")");
    }

    buf.prepend("(" + ExprProcessor.getCastTypeName(left) + ")");
  }

  @Override
  public void replaceExprent(Exprent oldExpr, Exprent newExpr) {
    if (oldExpr == left) {
      left = newExpr;
    }
    if (oldExpr == right) {
      right = newExpr;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) return true;
    if (!(o instanceof AssignmentExprent)) return false;

    AssignmentExprent as = (AssignmentExprent)o;
    return InterpreterUtil.equalObjects(left, as.getLeft()) &&
           InterpreterUtil.equalObjects(right, as.getRight()) &&
           condType == as.getCondType();
  }

  @Override
  public void getBytecodeRange(BitSet values) {
    measureBytecode(values, left);
    measureBytecode(values, right);
    measureBytecode(values);
  }

  // *****************************************************************************
  // getter and setter methods
  // *****************************************************************************

  public Exprent getLeft() {
    return left;
  }

  public Exprent getRight() {
    return right;
  }

  public void setRight(Exprent right) {
    this.right = right;
  }

  /**
   * the type of assignment, eg {@code =}, {@code +=}, {@code -=}, etc.
   */
  public int getCondType() {
    return condType;
  }

  public void setCondType(int condType) {
    this.condType = condType;
  }
}
