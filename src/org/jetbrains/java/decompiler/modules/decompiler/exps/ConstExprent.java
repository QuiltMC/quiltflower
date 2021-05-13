// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.modules.decompiler.exps;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericType;
import org.jetbrains.java.decompiler.main.collectors.BytecodeMappingTracer;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor;
import org.jetbrains.java.decompiler.struct.gen.FieldDescriptor;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.struct.match.MatchEngine;
import org.jetbrains.java.decompiler.struct.match.MatchNode;
import org.jetbrains.java.decompiler.struct.match.MatchNode.RuleValue;
import org.jetbrains.java.decompiler.util.InterpreterUtil;
import org.jetbrains.java.decompiler.util.TextBuffer;
import org.jetbrains.java.decompiler.util.TextUtil;

import java.util.*;
import java.util.Map.Entry;

public class ConstExprent extends Exprent {
  private static final Map<Integer, String> CHAR_ESCAPES = new HashMap<>();
  private static final Map<Double, String> UNINLINED_DOUBLES = new HashMap<>();
  private static final Map<Float, String> UNINLINED_FLOATS = new HashMap<>();

  static {
    CHAR_ESCAPES.put(0x8, "\\b");   /* \u0008: backspace BS */
    CHAR_ESCAPES.put(0x9, "\\t");   /* \u0009: horizontal tab HT */
    CHAR_ESCAPES.put(0xA, "\\n");   /* \u000a: linefeed LF */
    CHAR_ESCAPES.put(0xC, "\\f");   /* \u000c: form feed FF */
    CHAR_ESCAPES.put(0xD, "\\r");   /* \u000d: carriage return CR */
    //CHAR_ESCAPES.put(0x22, "\\\""); /* \u0022: double quote " */
    CHAR_ESCAPES.put(0x27, "\\'"); /* \u0027: single quote ' */
    CHAR_ESCAPES.put(0x5C, "\\\\"); /* \u005c: backslash \ */

    // Store float and double values that need to get uninlined.
    // These values are better represented by their original values, to improve code readability.
    // Some values need multiple float versions, as they can vary slightly due to where the cast to float is placed.
    // This patch is based on work in ForgeFlower submitted by Pokechu22.

    // Positive and negative e
    UNINLINED_DOUBLES.put(Math.E, "Math.E");
    UNINLINED_FLOATS.put((float) Math.E, "(float) Math.E");

    UNINLINED_DOUBLES.put(-Math.E, "-Math.E");
    UNINLINED_FLOATS.put((float) -Math.E, "(float) -Math.E");

    // Positive and negative pi
    UNINLINED_DOUBLES.put(Math.PI, "Math.PI");
    UNINLINED_FLOATS.put((float) Math.PI, "(float) Math.PI");

    UNINLINED_DOUBLES.put(-Math.PI, "-Math.PI");
    UNINLINED_FLOATS.put((float) -Math.PI, "(float) -Math.PI");

    // Positive and negative pi divisors
    for (int i = 2; i <= 20; i++) {
      UNINLINED_DOUBLES.put(Math.PI / i, "Math.PI / " + i);
      UNINLINED_FLOATS.put((float) Math.PI / i, "(float) Math.PI / " + i);
      UNINLINED_FLOATS.put((float) (Math.PI / i), "(float) (Math.PI / " + i + ")");

      UNINLINED_DOUBLES.put(-Math.PI / i, "-Math.PI / " + i);
      UNINLINED_FLOATS.put((float) -Math.PI / i, "(float) -Math.PI / " + i);
      UNINLINED_FLOATS.put((float) (-Math.PI / i), "(float) (-Math.PI / " + i + ")");
    }

    // Positive and negative pi multipliers
    for (int i = 2; i <= 20; i++) {
      UNINLINED_DOUBLES.put(Math.PI * i, "Math.PI * " + i);
      UNINLINED_FLOATS.put((float) Math.PI * i, "(float) Math.PI * " + i);
      UNINLINED_FLOATS.put((float) (Math.PI * i), "(float) (Math.PI * " + i + ")");

      UNINLINED_DOUBLES.put(-Math.PI * i, "-Math.PI * " + i);
      UNINLINED_FLOATS.put((float) -Math.PI * i, "(float) -Math.PI * " + i);
      UNINLINED_FLOATS.put((float) -Math.PI * i, "(float) (-Math.PI * " + i + ")");
    }

    // Extra pi values on the unit circle
    for (double numerator = 2; numerator < 13; numerator++) {
      for (double denominator = 2; denominator < 13; denominator++) {
        double gcd = gcd(numerator, denominator);
        if (gcd == 1) {
          UNINLINED_DOUBLES.put(Math.PI * (numerator / denominator), "(Math.PI * " + numerator + " / " + denominator + ")");
          UNINLINED_FLOATS.put((float) (Math.PI * (numerator / denominator)), "(float) (Math.PI * " + numerator + " / " + denominator + ")");
          UNINLINED_FLOATS.put((float) Math.PI * ((float) numerator / (float) denominator), "((float) Math.PI * " + (float) numerator + "F / " + (float) denominator + "F)");

          UNINLINED_DOUBLES.put(-Math.PI * (numerator / denominator), "(-Math.PI * " + numerator + " / " + denominator + ")");
          UNINLINED_FLOATS.put((float) (-Math.PI * (numerator / denominator)), "(float) (-Math.PI * " + numerator + " / " + denominator + ")");
          UNINLINED_FLOATS.put((float) -Math.PI * ((float) numerator / (float) denominator), "((float) -Math.PI * " + (float) numerator + "F / " + (float) denominator + "F)");
        }
      }
    }

    // Positive and negative 180 / pi
    UNINLINED_DOUBLES.put(180.0 / Math.PI, "180.0 / Math.PI");
    UNINLINED_FLOATS.put((float) (180.0F / Math.PI), "(float) (180.0F / Math.PI)");
    UNINLINED_FLOATS.put((180.0F / (float) Math.PI), " (180.0F / (float) Math.PI)");

    UNINLINED_DOUBLES.put(-180.0 / Math.PI, "-180.0 / Math.PI");
    UNINLINED_FLOATS.put((float) (-180.0F / Math.PI), "(float) (-180.0F / Math.PI)");
    UNINLINED_FLOATS.put((-180.0F / (float) Math.PI), "(-180.0F / (float) Math.PI)");

    // Positive and negative pi / 180
    // Since this is a floating point number divided by a whole fraction it doesn't need a reordered cast version
    UNINLINED_DOUBLES.put(Math.PI / 180.0, "Math.PI / 180.0");
    UNINLINED_FLOATS.put((float) (Math.PI / 180.0F), "(float) (Math.PI / 180.0F)");

    UNINLINED_DOUBLES.put(-Math.PI / 180.0, "-Math.PI / 180.0");
    UNINLINED_FLOATS.put((float) (-Math.PI / 180.0F), "(float) (-Math.PI / 180.0F)");
  }

  private VarType constType;
  private final Object value;
  private final boolean boolPermitted;

  public ConstExprent(int val, boolean boolPermitted, BitSet bytecodeOffsets) {
    this(guessType(val, boolPermitted), val, boolPermitted, bytecodeOffsets);
  }

  public ConstExprent(VarType constType, Object value, BitSet bytecodeOffsets) {
    this(constType, value, false, bytecodeOffsets);
  }

  private ConstExprent(VarType constType, Object value, boolean boolPermitted, BitSet bytecodeOffsets) {
    super(EXPRENT_CONST);
    this.constType = constType;
    this.value = value;
    this.boolPermitted = boolPermitted;
    addBytecodeOffsets(bytecodeOffsets);

    if (constType.equals(VarType.VARTYPE_CLASS) && value != null) {
      String stringVal = value.toString();
      List<VarType> args = Collections.singletonList(new VarType(stringVal, !stringVal.startsWith("[")));
      this.constType = new GenericType(constType.type, constType.arrayDim, constType.value, null, args, GenericType.WILDCARD_NO);
    }
  }

  private static VarType guessType(int val, boolean boolPermitted) {
    if (boolPermitted) {
      VarType constType = VarType.VARTYPE_BOOLEAN;
      if (val != 0 && val != 1) {
        constType = constType.copy(true);
      }
      return constType;
    }
    else if (0 <= val && val <= 127) {
      return VarType.VARTYPE_BYTECHAR;
    }
    else if (-128 <= val && val <= 127) {
      return VarType.VARTYPE_BYTE;
    }
    else if (0 <= val && val <= 32767) {
      return VarType.VARTYPE_SHORTCHAR;
    }
    else if (-32768 <= val && val <= 32767) {
      return VarType.VARTYPE_SHORT;
    }
    else if (0 <= val && val <= 0xFFFF) {
      return VarType.VARTYPE_CHAR;
    }
    else {
      return VarType.VARTYPE_INT;
    }
  }

  private static double gcd(double a, double b) {
    return b == 0 ? a : gcd(b, a%b);
  }

  @Override
  public Exprent copy() {
    return new ConstExprent(constType, value, bytecode);
  }

  @Override
  public VarType getExprType() {
    return constType;
  }

  @Override
  public int getExprentUse() {
    return Exprent.MULTIPLE_USES | Exprent.SIDE_EFFECTS_FREE;
  }

  @Override
  public List<Exprent> getAllExprents() {
    return new ArrayList<>();
  }

  @Override
  public TextBuffer toJava(int indent, BytecodeMappingTracer tracer) {
    boolean literal = DecompilerContext.getOption(IFernflowerPreferences.LITERALS_AS_IS);
    boolean ascii = DecompilerContext.getOption(IFernflowerPreferences.ASCII_STRING_CHARACTERS);

    tracer.addMapping(bytecode);

    if (constType.type != CodeConstants.TYPE_NULL && value == null) {
      return new TextBuffer(ExprProcessor.getCastTypeName(constType));
    }

    switch (constType.type) {
      case CodeConstants.TYPE_BOOLEAN:
        return new TextBuffer(Boolean.toString((Integer)value != 0));

      case CodeConstants.TYPE_CHAR:
        Integer val = (Integer)value;
        String ret = CHAR_ESCAPES.get(val);
        if (ret == null) {
          char c = (char)val.intValue();
          if (isPrintableAscii(c) || !ascii && TextUtil.isPrintableUnicode(c)) {
            ret = String.valueOf(c);
          }
          else {
            ret = TextUtil.charToUnicodeLiteral(c);
          }
        }
        return new TextBuffer(ret).enclose("'", "'");

      case CodeConstants.TYPE_BYTE:
      case CodeConstants.TYPE_BYTECHAR:
      case CodeConstants.TYPE_SHORT:
      case CodeConstants.TYPE_SHORTCHAR:
      case CodeConstants.TYPE_INT:
        int intVal = (Integer)value;
        if (!literal) {
          if (intVal == Integer.MAX_VALUE) {
            return new FieldExprent("MAX_VALUE", "java/lang/Integer", true, null, FieldDescriptor.INTEGER_DESCRIPTOR, bytecode).toJava(0, tracer);
          }
          else if (intVal == Integer.MIN_VALUE) {
            return new FieldExprent("MIN_VALUE", "java/lang/Integer", true, null, FieldDescriptor.INTEGER_DESCRIPTOR, bytecode).toJava(0, tracer);
          }
        }
        return new TextBuffer(value.toString());

      case CodeConstants.TYPE_LONG:
        long longVal = (Long)value;
        if (!literal) {
          if (longVal == Long.MAX_VALUE) {
            return new FieldExprent("MAX_VALUE", "java/lang/Long", true, null, FieldDescriptor.LONG_DESCRIPTOR, bytecode).toJava(0, tracer);
          }
          else if (longVal == Long.MIN_VALUE) {
            return new FieldExprent("MIN_VALUE", "java/lang/Long", true, null, FieldDescriptor.LONG_DESCRIPTOR, bytecode).toJava(0, tracer);
          }
        }
        return new TextBuffer(value.toString()).append('L');

      case CodeConstants.TYPE_FLOAT:
        return createFloat(literal, (Float)value, tracer);

      case CodeConstants.TYPE_DOUBLE:
        double doubleVal = (Double)value;
        if (!literal) {
          if (Double.isNaN(doubleVal)) {
            return new FieldExprent("NaN", "java/lang/Double", true, null, FieldDescriptor.DOUBLE_DESCRIPTOR, bytecode).toJava(0, tracer);
          }
          else if (doubleVal == Double.POSITIVE_INFINITY) {
            return new FieldExprent("POSITIVE_INFINITY", "java/lang/Double", true, null, FieldDescriptor.DOUBLE_DESCRIPTOR, bytecode).toJava(0, tracer);
          }
          else if (doubleVal == Double.NEGATIVE_INFINITY) {
            return new FieldExprent("NEGATIVE_INFINITY", "java/lang/Double", true, null, FieldDescriptor.DOUBLE_DESCRIPTOR, bytecode).toJava(0, tracer);
          }
          else if (doubleVal == Double.MAX_VALUE) {
            return new FieldExprent("MAX_VALUE", "java/lang/Double", true, null, FieldDescriptor.DOUBLE_DESCRIPTOR, bytecode).toJava(0, tracer);
          }
          else if (doubleVal == Double.MIN_NORMAL) {
            return new FieldExprent("MIN_NORMAL", "java/lang/Double", true, null, FieldDescriptor.DOUBLE_DESCRIPTOR, bytecode).toJava(0, tracer);
          }
          else if (doubleVal == Double.MIN_VALUE) {
            return new FieldExprent("MIN_VALUE", "java/lang/Double", true, null, FieldDescriptor.DOUBLE_DESCRIPTOR, bytecode).toJava(0, tracer);
          }
          else if (UNINLINED_DOUBLES.containsKey(doubleVal)) {
            return new TextBuffer(UNINLINED_DOUBLES.get(doubleVal));
          }

          // Try to convert the double representation of the value to the float representation, to output the cleanest version of the value.
          // This patch is based on work in ForgeFlower submitted by Pokechu22.
          float floatRepresentation = (float) doubleVal;
          if (floatRepresentation == doubleVal) {
            if (Float.toString(floatRepresentation).length() < Double.toString(doubleVal).length()) {
              // Check the uninlined values to see if we have one of those
              if (UNINLINED_FLOATS.containsKey(floatRepresentation)) {
                return new TextBuffer(UNINLINED_FLOATS.get(floatRepresentation));
              } else {
                // Return the standard representation if the value is not able to be uninlined
                return new TextBuffer(Float.toString(floatRepresentation)).append("F");
              }
            }
          }
        }
        else if (Double.isNaN(doubleVal)) {
          return new TextBuffer("0.0 / 0.0");
        }
        else if (doubleVal == Double.POSITIVE_INFINITY) {
          return new TextBuffer("1.0 / 0.0");
        }
        else if (doubleVal == Double.NEGATIVE_INFINITY) {
          return new TextBuffer("-1.0 / 0.0");
        }
        return new TextBuffer(trimZeros(value.toString()));

      case CodeConstants.TYPE_NULL:
        return new TextBuffer("null");

      case CodeConstants.TYPE_OBJECT:
        if (constType.equals(VarType.VARTYPE_STRING)) {
          return new TextBuffer(convertStringToJava(value.toString(), ascii)).enclose("\"", "\"");
        }
        else if (constType.equals(VarType.VARTYPE_CLASS)) {
          String stringVal = value.toString();
          VarType type = new VarType(stringVal, !stringVal.startsWith("["));
          return new TextBuffer(ExprProcessor.getCastTypeName(type)).append(".class");
        }
    }

    throw new RuntimeException("invalid constant type: " + constType);
  }

  private TextBuffer createFloat(boolean literal, float floatVal, BytecodeMappingTracer tracer) {
    if (!literal) {
      // Float constants, some of which can't be represented directly
      if (Float.isNaN(floatVal)) {
        return new FieldExprent("NaN", "java/lang/Float", true, null, FieldDescriptor.FLOAT_DESCRIPTOR, bytecode).toJava(0, tracer);
      }
      else if (floatVal == Float.POSITIVE_INFINITY) {
        return new FieldExprent("POSITIVE_INFINITY", "java/lang/Float", true, null, FieldDescriptor.FLOAT_DESCRIPTOR, bytecode).toJava(0, tracer);
      }
      else if (floatVal == Float.NEGATIVE_INFINITY) {
        return new FieldExprent("NEGATIVE_INFINITY", "java/lang/Float", true, null, FieldDescriptor.FLOAT_DESCRIPTOR, bytecode).toJava(0, tracer);
      }
      else if (floatVal == Float.MAX_VALUE) {
        return new FieldExprent("MAX_VALUE", "java/lang/Float", true, null, FieldDescriptor.FLOAT_DESCRIPTOR, bytecode).toJava(0, tracer);
      }
      else if (floatVal == Float.MIN_NORMAL) {
        return new FieldExprent("MIN_NORMAL", "java/lang/Float", true, null, FieldDescriptor.FLOAT_DESCRIPTOR, bytecode).toJava(0, tracer);
      }
      else if (floatVal == Float.MIN_VALUE) {
        return new FieldExprent("MIN_VALUE", "java/lang/Float", true, null, FieldDescriptor.FLOAT_DESCRIPTOR, bytecode).toJava(0, tracer);
      }
      else if (floatVal == -Float.MAX_VALUE) {
        return new FieldExprent("MAX_VALUE", "java/lang/Float", true, null, FieldDescriptor.FLOAT_DESCRIPTOR, bytecode).toJava(0, tracer).prepend("-");
      }
      else if (floatVal == -Float.MIN_NORMAL) {
        return new FieldExprent("MIN_NORMAL", "java/lang/Float", true, null, FieldDescriptor.FLOAT_DESCRIPTOR, bytecode).toJava(0, tracer).prepend("-");
      }
      else if (floatVal == -Float.MIN_VALUE) {
        return new FieldExprent("MIN_VALUE", "java/lang/Float", true, null, FieldDescriptor.FLOAT_DESCRIPTOR, bytecode).toJava(0, tracer).prepend("-");
      }
      // Math constants
      else if (floatVal == (float)Math.E) {
        return new FieldExprent("E", "java/lang/Math", true, null, FieldDescriptor.DOUBLE_DESCRIPTOR, bytecode).toJava(0, tracer).prepend("(float)");
      }
      else if (UNINLINED_FLOATS.containsKey(floatVal)) {
        return new TextBuffer(UNINLINED_FLOATS.get(floatVal));
      }
    }
    else {
      // Check for special values that can't be used directly in code
      // (and we can't replace with the constant due to the user requesting not to)
      if (Float.isNaN(floatVal)) {
        return new TextBuffer("0.0F / 0.0F");
      }
      else if (floatVal == Float.POSITIVE_INFINITY) {
        return new TextBuffer("1.0F / 0.0F");
      }
      else if (floatVal == Float.NEGATIVE_INFINITY) {
        return new TextBuffer("-1.0F / 0.0F");
      }
    }
    return new TextBuffer(trimZeros(Float.toString(floatVal))).append('F');
  }

  private TextBuffer getPiDouble(BytecodeMappingTracer tracer) {
    return new FieldExprent("PI", "java/lang/Math", true, null, FieldDescriptor.DOUBLE_DESCRIPTOR, bytecode).toJava(0, tracer);
  }

  private TextBuffer getPiFloat(BytecodeMappingTracer tracer) {
    // java.lang.Math doesn't have a float version of pi, unfortunately
    return getPiDouble(tracer).prepend("(float)");
  }

  // Different JVM implementations/version display Floats and Doubles with different number of trailing zeros.
  // This trims them all down to only the necessary amount.
  private static String trimZeros(String value) {
      int i = value.length() - 1;
      while (i >= 0 && value.charAt(i) == '0') {
          i--;
      }
      if (value.charAt(i) == '.')
        i++;
      return value.substring(0, i + 1);
  }

  public boolean isNull() {
    return CodeConstants.TYPE_NULL == constType.type;
  }

  private static String convertStringToJava(String value, boolean ascii) {
    char[] arr = value.toCharArray();
    StringBuilder buffer = new StringBuilder(arr.length);

    for (char c : arr) {
      switch (c) {
        case '\\': //  u005c: backslash \
          buffer.append("\\\\");
          break;
        case 0x8: // "\\\\b");  //  u0008: backspace BS
          buffer.append("\\b");
          break;
        case 0x9: //"\\\\t");  //  u0009: horizontal tab HT
          buffer.append("\\t");
          break;
        case 0xA: //"\\\\n");  //  u000a: linefeed LF
          buffer.append("\\n");
          break;
        case 0xC: //"\\\\f");  //  u000c: form feed FF
          buffer.append("\\f");
          break;
        case 0xD: //"\\\\r");  //  u000d: carriage return CR
          buffer.append("\\r");
          break;
        case 0x22: //"\\\\\""); // u0022: double quote "
          buffer.append("\\\"");
          break;
        //case 0x27: //"\\\\'");  // u0027: single quote '
        //  buffer.append("\\\'");
        //  break;
        default:
          if (isPrintableAscii(c) || !ascii && TextUtil.isPrintableUnicode(c)) {
            buffer.append(c);
          }
          else {
            buffer.append(TextUtil.charToUnicodeLiteral(c));
          }
      }
    }

    return buffer.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) return true;
    if (!(o instanceof ConstExprent)) return false;

    ConstExprent cn = (ConstExprent)o;
    return InterpreterUtil.equalObjects(constType, cn.getConstType()) &&
           InterpreterUtil.equalObjects(value, cn.getValue());
  }

  @Override
  public int hashCode() {
    int result = constType != null ? constType.hashCode() : 0;
    result = 31 * result + (value != null ? value.hashCode() : 0);
    return result;
  }

  public boolean hasBooleanValue() {
    switch (constType.type) {
      case CodeConstants.TYPE_BOOLEAN:
      case CodeConstants.TYPE_CHAR:
      case CodeConstants.TYPE_BYTE:
      case CodeConstants.TYPE_BYTECHAR:
      case CodeConstants.TYPE_SHORT:
      case CodeConstants.TYPE_SHORTCHAR:
      case CodeConstants.TYPE_INT:
        int value = (Integer)this.value;
        return value == 0 || (DecompilerContext.getOption(IFernflowerPreferences.BOOLEAN_TRUE_ONE) && value == 1);
    }

    return false;
  }

  public boolean hasValueOne() {
    switch (constType.type) {
      case CodeConstants.TYPE_BOOLEAN:
      case CodeConstants.TYPE_CHAR:
      case CodeConstants.TYPE_BYTE:
      case CodeConstants.TYPE_BYTECHAR:
      case CodeConstants.TYPE_SHORT:
      case CodeConstants.TYPE_SHORTCHAR:
      case CodeConstants.TYPE_INT:
        return (Integer)value == 1;
      case CodeConstants.TYPE_LONG:
        return ((Long)value).intValue() == 1;
      case CodeConstants.TYPE_DOUBLE:
        return ((Double)value).intValue() == 1;
      case CodeConstants.TYPE_FLOAT:
        return ((Float)value).intValue() == 1;
    }

    return false;
  }

  public static ConstExprent getZeroConstant(int type) {
    switch (type) {
      case CodeConstants.TYPE_INT:
        return new ConstExprent(VarType.VARTYPE_INT, 0, null);
      case CodeConstants.TYPE_LONG:
        return new ConstExprent(VarType.VARTYPE_LONG, 0L, null);
      case CodeConstants.TYPE_DOUBLE:
        return new ConstExprent(VarType.VARTYPE_DOUBLE, 0d, null);
      case CodeConstants.TYPE_FLOAT:
        return new ConstExprent(VarType.VARTYPE_FLOAT, 0f, null);
    }

    throw new RuntimeException("Invalid argument: " + type);
  }

  public VarType getConstType() {
    return constType;
  }

  public void setConstType(VarType constType) {
    this.constType = constType;
  }

  public void adjustConstType(VarType expectedType) {
    // BYTECHAR and SHORTCHAR => CHAR in the CHAR context
    if ((expectedType.equals(VarType.VARTYPE_CHAR) || expectedType.equals(VarType.VARTYPE_CHARACTER)) &&
            (constType.equals(VarType.VARTYPE_BYTECHAR) || constType.equals(VarType.VARTYPE_SHORTCHAR))) {
      int intValue = getIntValue();
      if (isPrintableAscii(intValue) || CHAR_ESCAPES.containsKey(intValue)) {
        setConstType(VarType.VARTYPE_CHAR);
      }
    }
    // BYTE, BYTECHAR, SHORTCHAR, SHORT, CHAR => INT in the INT context
    else if ((expectedType.equals(VarType.VARTYPE_INT) || expectedType.equals(VarType.VARTYPE_INTEGER)) &&
            constType.typeFamily == CodeConstants.TYPE_FAMILY_INTEGER) {
      setConstType(VarType.VARTYPE_INT);
    }
  }

  private static boolean isPrintableAscii(int c) {
    return c >= 32 && c < 127;
  }

  public Object getValue() {
    return value;
  }

  public int getIntValue() {
    return (Integer)value;
  }

  public boolean isBoolPermitted() {
    return boolPermitted;
  }

  @Override
  public void getBytecodeRange(BitSet values) {
    measureBytecode(values);
  }

  @Override
  public String toString() {
    return "const(" + toJava(0, new BytecodeMappingTracer()) + ")";
  }

  // *****************************************************************************
  // IMatchable implementation
  // *****************************************************************************

  @Override
  public boolean match(MatchNode matchNode, MatchEngine engine) {
    if (!super.match(matchNode, engine)) {
      return false;
    }

    for (Entry<MatchProperties, RuleValue> rule : matchNode.getRules().entrySet()) {
      RuleValue value = rule.getValue();
      MatchProperties key = rule.getKey();

      if (key == MatchProperties.EXPRENT_CONSTTYPE) {
        if (!value.value.equals(this.constType)) {
          return false;
        }
      }
      else if (key == MatchProperties.EXPRENT_CONSTVALUE) {
        if (value.isVariable() && !engine.checkAndSetVariableValue(value.value.toString(), this.value)) {
          return false;
        }
      }
    }

    return true;
  }
}