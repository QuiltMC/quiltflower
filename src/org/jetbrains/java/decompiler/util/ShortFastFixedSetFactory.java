// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.util;


import java.util.*;

class ShortFastFixedSetFactory<E> extends FastFixedSetFactory<E> {
  private final Map<E, Long> masks = new LinkedHashMap<>();
  private final long full;

  ShortFastFixedSetFactory(Collection<E> set) {
    assert set.size() <= 64;

    long mask = 1;

    for (E element : set) {
      this.masks.put(element, mask);
      mask <<= 1;
    }

    this.full = mask - 1;
  }

  @Override
  public FastFixedSet<E> spawnEmptySet() {
    return new ShortFastFixedSet();
  }

  @Override
  public Collection<? extends E> getEntries() {
    return this.masks.keySet();
  }

  public final class ShortFastFixedSet extends FastFixedSet<E> {
    private long data = 0;

    private ShortFastFixedSet() {
      super(ShortFastFixedSetFactory.this);
    }

    private ShortFastFixedSet(long data) {
      super(ShortFastFixedSetFactory.this);
      this.data = data;
    }

    @Override
    public FastFixedSet<E> clone() {
      return new ShortFastFixedSet(this.data);
    }

    @Override
    public void setAllElements() {
      this.data = ShortFastFixedSetFactory.this.full;
    }

    @Override
    public boolean add(E element) {
      long mask = ShortFastFixedSetFactory.this.masks.get(element);
      return this.data != (this.data |= mask);
    }

    @Override
    public void clear() {
      this.data = 0;
    }

    @SuppressWarnings("SuspiciousMethodCalls")
    public boolean remove(Object element) {
      long mask = ShortFastFixedSetFactory.this.masks.get(element);
      return this.data != (this.data &= ~mask);
    }

    @Override
    @SuppressWarnings("SuspiciousMethodCalls")
    public boolean contains(Object element) {
      long mask = ShortFastFixedSetFactory.this.masks.get(element);
      return (this.data & mask) != 0;
    }

    @Override
    public boolean containsAll(FastFixedSet<E> set) {
      return (this.getData(set) & ~this.data) == 0;
    }

    private long getData(FastFixedSet<E> set) {
      assert set.factory == ShortFastFixedSetFactory.this;
      return ((ShortFastFixedSet) set).data;
    }

    @Override
    public boolean addAll(FastFixedSet<E> set) {
      return this.data != (this.data |= this.getData(set));
    }

    @Override
    public boolean retainAll(FastFixedSet<E> set) {
      return this.data != (this.data &= this.getData(set));
    }

    @Override
    public boolean removeAll(FastFixedSet<E> set) {
      return this.data != (this.data &= ~this.getData(set));
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || this.getClass() != o.getClass()) return false;
      ShortFastFixedSet that = (ShortFastFixedSet) o;
      return ShortFastFixedSetFactory.this == that.factory && this.data == that.data;
    }

    @Override
    public int hashCode() {
      return 31 * ShortFastFixedSetFactory.this.hashCode() + Long.hashCode(this.data);
    }

    @Override
    public int size() {
      return Long.bitCount(this.data);
    }

    public boolean isEmpty() {
      return this.data == 0;
    }

    @Override
    public Iterator<E> iterator() {
      return new LongFastFixedSetIterator();
    }

    @Override
    public String toString() {
      StringJoiner buffer = new StringJoiner(",", "{", "}");

      long data = this.data;

      ShortFastFixedSetFactory.this.masks.forEach((item, mask) -> {
        if ((data & mask) != 0) {
          buffer.add(item.toString());
        }
      });

      return buffer.toString();
    }

    private final class LongFastFixedSetIterator implements Iterator<E> {
      private final Iterator<Map.Entry<E, Long>> data = ShortFastFixedSetFactory.this.masks.entrySet().iterator();
      private Map.Entry<E, Long> entry;
      private Map.Entry<E, Long> lastEntry;

      @Override
      public boolean hasNext() {
        while (this.data.hasNext()) {
          this.entry = this.data.next();
          long mask = this.entry.getValue();
          if ((ShortFastFixedSet.this.data & mask) != 0) {
            return true;
          }
        }

        this.entry = null;
        return false;
      }

      @Override
      public E next() {
        if (this.entry == null && !this.hasNext()) {
          throw new NoSuchElementException();
        }
        assert this.entry != null;

        this.lastEntry = this.entry;
        this.entry = null;
        return this.lastEntry.getKey();
      }

      @Override
      public void remove() {
        long mask = this.lastEntry.getValue();
        ShortFastFixedSet.this.data &= ~mask;
      }
    }
  }
}