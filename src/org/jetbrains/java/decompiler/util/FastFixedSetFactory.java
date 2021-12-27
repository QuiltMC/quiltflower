package org.jetbrains.java.decompiler.util;

import java.util.Collection;

public abstract class FastFixedSetFactory<E> {
  public abstract FastFixedSet<E> spawnEmptySet();

  public abstract Collection<? extends E> getEntries();

  public static <E>FastFixedSetFactory<E> create(Collection<E> entries){
    if(entries.size() <= 64){
      return new ShortFastFixedSetFactory<>(entries);
    } else {
      return new LongFastFixedSetFactory<>(entries);
    }
  }
}
