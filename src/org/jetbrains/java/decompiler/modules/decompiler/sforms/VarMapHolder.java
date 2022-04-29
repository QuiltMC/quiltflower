package org.jetbrains.java.decompiler.modules.decompiler.sforms;

import org.jetbrains.java.decompiler.util.SFormsFastMapDirect;

/*
 * The VarMapHolder class is used to hold the variable maps
 *
 * It can be in 2 different states:
 * - normal when `ifFalse` is null
 * - split when `ifFalse` is not null
 */
class VarMapHolder {
  private SFormsFastMapDirect ifTrue;  // not null
  private SFormsFastMapDirect ifFalse; // nullable

  private VarMapHolder(SFormsFastMapDirect ifTrue, SFormsFastMapDirect ifFalse) {
    this.ifTrue = ifTrue;
    this.ifFalse = ifFalse;
  }

  static VarMapHolder ofNormal(SFormsFastMapDirect holder) {
    return new VarMapHolder(new SFormsFastMapDirect(holder), null);
  }

  // caller should not mutate this map unless `makeFullyMutable()` has been called
  SFormsFastMapDirect getIfTrue() {
    return this.ifTrue;
  }

  // caller should not mutate this map unless `makeFullyMutable()` has been called
  SFormsFastMapDirect getIfFalse() {
    return this.ifFalse == null ? this.ifTrue : this.ifFalse;
  }

  // mutates ifTrue and ifFalse
  SFormsFastMapDirect toNormal() {
    final SFormsFastMapDirect result = mergeMaps(this.ifTrue, this.ifFalse);
    this.ifFalse = null;
    return result;
  }

  SFormsFastMapDirect getNormal() {
    assert this.isNormal();

    return this.ifTrue;
  }

  void setIfTrue(SFormsFastMapDirect ifTrue) {
    if(this.ifFalse == null) {
      // make sure we don't override getIfFalse()
      this.ifFalse = this.ifTrue;
    }

    this.ifTrue = ifTrue;
  }

  void setIfFalse(SFormsFastMapDirect ifFalse) {
    this.ifFalse = ifFalse;
  }

  void setNormal(SFormsFastMapDirect normal) {
    this.ifFalse = null;
    this.ifTrue = normal;
  }

  public void set(VarMapHolder bVarMaps) {
    this.ifTrue = bVarMaps.ifTrue;
    this.ifFalse = bVarMaps.ifFalse;
  }

  void mergeIfTrue(SFormsFastMapDirect map2) {
    if(this.ifTrue == map2 || map2 == null || map2.isEmpty()) {
      return;
    }

    this.makeFullyMutable();
    this.ifTrue.union(map2);
  }

  void mergeIfFalse(SFormsFastMapDirect map2) {
    if(this.ifFalse == map2 || map2 == null || map2.isEmpty()) {
      return;
    }

    this.makeFullyMutable();
    this.ifFalse.union(map2);
  }

  boolean isNormal() {
    return this.ifFalse == null;
  }

  void swap() {
    if(this.ifFalse == null) {
      return;
    }

    final SFormsFastMapDirect tmp = this.ifTrue;
    this.ifTrue = this.ifFalse;
    this.ifFalse = tmp;
  }

  void makeFullyMutable() {
    if(this.ifFalse != null && this.ifTrue != this.ifFalse) {
      return;
    }

    this.ifFalse = new SFormsFastMapDirect(this.ifTrue);
  }


  public static SFormsFastMapDirect mergeMaps(SFormsFastMapDirect mapTo, SFormsFastMapDirect map2) {

    if (mapTo != map2 && map2 != null && !map2.isEmpty()) {
      mapTo.union(map2);
    }

    return mapTo;
  }
}
