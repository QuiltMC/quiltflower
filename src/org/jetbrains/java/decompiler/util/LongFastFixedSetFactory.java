// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.util;


import java.util.*;

class LongFastFixedSetFactory<E> extends FastFixedSetFactory<E> {
  private final Map<E, Integer> indexes = new LinkedHashMap<>();
  private final int dataLength;

  LongFastFixedSetFactory(Collection<E> set) {
    this.dataLength = set.size() + 63 >> 6;

    int index = 0;

    for (E element : set) {
      this.indexes.put(element, index++);
    }
  }

  @Override
  public FastFixedSet<E> spawnEmptySet() {
    return new LongFastFixedSet();
  }

  @Override
  public Collection<? extends E> getEntries() {
    return this.indexes.keySet();
  }

  public final class LongFastFixedSet extends FastFixedSet<E> {
    private final long[] data;

    private LongFastFixedSet() {
      super(LongFastFixedSetFactory.this);
      this.data = new long[LongFastFixedSetFactory.this.dataLength];
    }

    private LongFastFixedSet(long[] data) {
      super(LongFastFixedSetFactory.this);
      this.data = data;
    }

    @Override
    public FastFixedSet<E> clone() {
      return new LongFastFixedSet(this.data.clone());
    }

    @Override
    public void setAllElements() {
      Arrays.fill(this.data, 0, this.data.length - 1, -1L);
      this.data[this.data.length - 1] = (1L << (LongFastFixedSetFactory.this.indexes.size() & 63)) - 1;
    }

    @Override
    public boolean add(E element) {
      int index = LongFastFixedSetFactory.this.indexes.get(element);
      return this.data[index >> 6] != (this.data[index >> 6] |= 1L << (index & 63));
    }

    @Override
    public void clear() {
      Arrays.fill(this.data, 0L);
    }

    @SuppressWarnings("SuspiciousMethodCalls")
    public boolean remove(Object element) {
      int index = LongFastFixedSetFactory.this.indexes.get(element);
      return this.data[index >> 6] != (this.data[index >> 6] &= ~(1L << (index & 63)));
    }

    @Override
    @SuppressWarnings("SuspiciousMethodCalls")
    public boolean contains(Object element) {
      int index = LongFastFixedSetFactory.this.indexes.get(element);
      return (this.data[index >> 6] & 1L << (index & 63)) != 0;
    }

    @Override
    public boolean containsAll(FastFixedSet<E> set) {
      long[] intdata = this.data;
      long[] extdata = this.getData(set);

      for (int i = intdata.length - 1; i >= 0; i--) {
        if ((extdata[i] & ~intdata[i]) != 0) {
          return false;
        }
      }

      return true;
    }

    private long[] getData(FastFixedSet<E> set) {
      assert set.factory == LongFastFixedSetFactory.this;
      return ((LongFastFixedSet) set).data;
    }


    @Override
    public boolean addAll(FastFixedSet<E> set) {
      long[] intdata = this.data;
      long[] extdata = this.getData(set);

      boolean mutated = false;
      for (int i = intdata.length - 1; i >= 0; i--) {
        mutated |= intdata[i] != (intdata[i] |= extdata[i]);
      }

      return mutated;
    }

    @Override
    public boolean retainAll(FastFixedSet<E> set) {
      long[] intdata = this.data;
      long[] extdata = this.getData(set);

      boolean mutated = false;
      for (int i = intdata.length - 1; i >= 0; i--) {
        mutated |= intdata[i] != (intdata[i] &= extdata[i]);
      }

      return mutated;
    }

    @Override
    public boolean removeAll(FastFixedSet<E> set) {
      long[] intdata = this.data;
      long[] extdata = this.getData(set);

      boolean mutated = false;
      for (int i = intdata.length - 1; i >= 0; i--) {
        mutated |= intdata[i] != (intdata[i] &= ~extdata[i]);
      }

      return mutated;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || this.getClass() != o.getClass()) return false;
      LongFastFixedSet that = (LongFastFixedSet) o;
      return LongFastFixedSetFactory.this == that.factory && Arrays.equals(this.data, that.data);
    }

    @Override
    public int hashCode() {
      return 31 * LongFastFixedSetFactory.this.hashCode() + Arrays.hashCode(this.data);
    }

    @Override
    public int size() {
      int size = 0;
      for (long d : this.data) {
        size += Long.bitCount(d);
      }

      return size;
    }

    public boolean isEmpty() {
      for (long d : this.data) {
        if (d != 0) {
          return false;
        }
      }
      return true;
    }

    @Override
    public Iterator<E> iterator() {
      return new LongFastFixedSetIterator();
    }

    @Override
    public String toString() {
      StringJoiner buffer = new StringJoiner(",", "{", "}");

      long[] data = this.data;

      LongFastFixedSetFactory.this.indexes.forEach((item, i) -> {
        if ((data[i >> 6] & (1L << (i & 63))) != 0) {
          buffer.add(item.toString());
        }
      });

      return buffer.toString();
    }

    private final class LongFastFixedSetIterator implements Iterator<E> {
      private final Iterator<Map.Entry<E, Integer>> data = LongFastFixedSetFactory.this.indexes.entrySet().iterator();
      private Map.Entry<E, Integer> entry;
      private Map.Entry<E, Integer> lastEntry;

      @Override
      public boolean hasNext() {
        while (this.data.hasNext()) {
          this.entry = this.data.next();
          int index = this.entry.getValue();
          if ((LongFastFixedSet.this.data[index >> 6] & 1L << (index & 63)) != 0) {
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
        int index = this.lastEntry.getValue();
        LongFastFixedSet.this.data[index >> 6] &= ~(1L << (index & 63));
      }
    }
  }
}