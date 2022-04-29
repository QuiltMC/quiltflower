package pkg;

public class TestPatternMatching17FakeLoops {
  void test1(Object o) {
    while (o instanceof String) {
      final String s = (String) o;
      System.out.println(s.length());
    }
  }

  void test2(Object o) {
    while (o instanceof String x) {
      final String s = (String) o;
      System.out.println(s);
    }
  }

  void test3(Object o) {
    while (o.hashCode() < 0 || o instanceof String) {
      final String s = (String) o;
      System.out.println(s);
    }
  }

  void test3B(Object o) {
    while (o.hashCode() < 0 || o instanceof String) {
      final String s = (String) o;
      System.out.println(s);
    }

    System.out.println("bye");
  }

  void test4(Object o) {
    while (o.hashCode() < 0 || o instanceof String x) {
      final String s = (String) o;
      System.out.println(s);
    }
  }

  void test4x(Object o) {
    while (o.hashCode() < 0 || o instanceof String x && !x.isEmpty()) {
      final String s = (String) o;
      System.out.println(s);
    }
  }

  void test4B(Object o) {
    while (o.hashCode() < 0 || o instanceof String x) {
      final String s = (String) o;
      System.out.println(s);
    }
    System.out.println("bye");
  }

  void test4xB(Object o) {
    while (o.hashCode() < 0 || o instanceof String x && !x.isEmpty()) {
      final String s = (String) o;
      System.out.println(s);
    }
    System.out.println("bye");
  }

  void test3Swap(Object o) {
    while (o instanceof String || o.hashCode() < 0) {
      final String s = (String) o;
      System.out.println(s);
    }
  }

  void test3BSwap(Object o) {
    while (o instanceof String || o.hashCode() < 0) {
      final String s = (String) o;
      System.out.println(s);
    }

    System.out.println("bye");
  }

  void test4Swap(Object o) {
    while (o instanceof String x || o.hashCode() < 0) {
      final String s = (String) o;
      System.out.println(s);
    }
  }

  void test4xSwap(Object o) {
    while (o instanceof String x && !x.isEmpty() || o.hashCode() < 0) {
      final String s = (String) o;
      System.out.println(s);
    }
  }

  void test4BSwap(Object o) {
    while (o instanceof String x || o.hashCode() < 0) {
      final String s = (String) o;
      System.out.println(s);
    }
    System.out.println("bye");
  }

  void test4xBSwap(Object o) {
    while (o instanceof String x && !x.isEmpty() || o.hashCode() < 0) {
      final String s = (String) o;
      System.out.println(s);
    }
    System.out.println("bye");
  }


  public void testSet(Object obj) {
    String s = "Hi";
    while (obj instanceof String) {
      s = (String) obj;
    }

    System.out.println(s);
  }


  public void testModifyLeak(Object obj) {
    Integer i = null;
    while (obj instanceof Integer) {
      i = (Integer) obj;

      if (i > 4) {
        i += 3;
      }
    }

    if (i != null) {
      System.out.println(i);
    }
  }
}
