package pkg;

public class TestPatternMatchingInteger {
    public int testSimple(Object obj) {
        if (obj instanceof Integer i) {
            return i + 1;
        }

        return 1;
    }

    public int testMulti(Object a, Object b) {
        if (a instanceof Integer ai && b instanceof Integer bi) {
            return ai + bi;
        } else if (a instanceof Integer ai) {
            return ai;
        } else if (b instanceof Integer bi) {
            return bi;
        }

        return 0;
    }

    public int testMulti2(Object a, Object b) {
        int result = 0;
        if (a instanceof Integer ai) {
            result += ai;
        }
        if (b instanceof Integer bi) {
            result += bi;
        }

        return result;
    }

    public int testMultiDifferent(Object a, Object b) {
        if (a instanceof Integer i && b instanceof String s) {
            return i + s.length();
        }

        return 0;
    }

    public void testDeMorgan(Object obj) {
        if (!(obj instanceof Integer i && i >= 1 && !(i < 41))) {
            System.out.println("i");
        } else {
            System.out.println(i);
        }
    }
}
