package pkg;

import java.io.UncheckedIOException;

public class TestTryCatchNested {
  public void test() {
    float var1 = 20F;
    try {
      try {
        System.out.println(var1);
        return;
      } catch (Exception var7) {
      }
    } catch (Exception var10) {
      System.out.println(var1);
    }
  }

  public void test1() {
    float var1 = 20F;
    try {
      try {
        System.out.println(var1);
        return;
      } catch (Exception var7) {
      }
    } catch (UncheckedIOException uio) {
      System.out.println(uio.getCause());
    } catch (Exception var10) {
      System.out.println(var1);
    }
  }

  public void test2() {
    float var1 = 20F;
    try {
      try {
        System.out.println(var1);
        return;
      } catch (Exception var7) {
      }
    } catch (Exception var10) {
      System.out.println(var1);
    } catch (Throwable tb) {
      System.out.println(tb.getCause());
    }
  }
}
