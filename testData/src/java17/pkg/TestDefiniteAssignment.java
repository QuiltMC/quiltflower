package pkg;

import java.io.IOException;

public class TestDefiniteAssignment {
  void testExample16$1$$1(int v) throws IOException {
    int k;
    if (v > 0 && (k = System.in.read()) >= 0)
      System.out.println(k);
  }

  void testExample16$1$$2(int n) {
    {
      int k;
      while (true) {
        k = n;
        if (k >= 5) break;
        n = 6;
      }
      System.out.println(k);
    }
  }

  void testExample16$1$$3modified(int n, int m) {
    int k;
    while (n < 4 || (k = m) < 5) {
      k = n;
      if (k >= 5) break;
      n = 6;
    }
    System.out.println(k);
  }

  void testAssignments(int n, boolean bool) {
    int a;
    if (bool && ((a = n) > 0 || (a = -n) > 100)) {
      System.out.println(a);
    }

    int b;
    if (bool || (b = (b = n) * b) > 0) {
      System.out.println("b");
    } else {
      System.out.println(b);
    }

    {
      double cFake = 0.01;
      System.out.println(cFake);
    }

    double c;
    if (!((n < 1.0 - n) && (c = (n + 5)) > (c * c - c / 2)) ? n < 5.0 - (c = n) : n > c) {
      System.out.println(c);
      c += 2;
    } else {
      c += 5;
    }
    System.out.println(c);

    boolean x;
    double d;
    if (x = ((d = n) > 0)) {
      System.out.println(d);
    }

  }

  void testBooleanNormalness(int n) {
    int k;
    int p = n * 2;
    if (n < 5 && (k = n + 5) > 0 && (p /= k) != 0) {
      System.out.println("hi");
    }
    p += 8;
    System.out.println(p);
  }

  void testBooleanNormalnessInline(int n) {
    int k;
    int p = n * 2;
    Boolean.valueOf(n < 5 && (k = n + 5) > 0 && (p /= k) != 0);
    p += 8;
    System.out.println(p);
  }

  void nestedTernaries(int a, int b, int c) {
    int x;
    if ((a > 0 ? c < b && (x = b) > 0 : (x = c) < 0 || c == b)) {
      System.out.println(x);
    }

    int y, z;
    if ((a > 0 ? c < b && (y = b) > 0 : (y = c) < 0 || c == b)
      ? 1 > b - c && (z = b - c) != a
      : (y = 5) != (z = a)) {
      System.out.println(z);
    }
    System.out.println(y);
  }
}
