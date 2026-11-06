import com.kanfan.player.Swipe;

/**
 * 翻页判定的纯算术用例：方向、阈值、边缘、阻尼。
 * 这些符号一旦写反，界面看起来「只是反了一下」，很难靠肉眼发现。
 */
public class SwipeTest {

    private static int passed = 0;
    private static int failed = 0;

    static void check(String name, int actual, int expect) {
        if (actual == expect) {
            passed++;
        } else {
            failed++;
            System.out.println("  ✗ " + name + "  期望 " + expect + " 实际 " + actual);
        }
    }

    static void checkF(String name, float actual, float expect, float tol) {
        if (Math.abs(actual - expect) <= tol) {
            passed++;
        } else {
            failed++;
            System.out.println("  ✗ " + name + "  期望 " + expect + " 实际 " + actual);
        }
    }

    static void checkB(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("  ✗ " + name);
        }
    }

    public static void main(String[] args) {
        final int W = 1000;
        final float FLING = 50f;

        // 1) 位移超过 28% 就翻页（向左拖 = 下一张）
        check("拖 30% → 下一张", Swipe.decide(-300, 0, W, 3, 10, FLING), 1);
        check("拖 30% → 上一张", Swipe.decide(300, 0, W, 3, 10, FLING), -1);

        // 2) 位移不够、也没速度 → 回弹
        check("拖 20% 无速度 → 回弹", Swipe.decide(-200, 0, W, 3, 10, FLING), 0);
        check("拖 5% 无速度 → 回弹", Swipe.decide(-50, 0, W, 3, 10, FLING), 0);

        // 3) 位移不够但甩得够快 → 翻页
        check("拖 10% 快甩 → 下一张", Swipe.decide(-100, -2000, W, 3, 10, FLING), 1);
        check("拖 10% 快甩 → 上一张", Swipe.decide(100, 2000, W, 3, 10, FLING), -1);

        // 4) 速度方向与位移方向相反时不算数
        check("拖 10% 但速度反向 → 回弹", Swipe.decide(-100, 2000, W, 3, 10, FLING), 0);

        // 5) 快到 6% 门槛之下，即使很快也不翻
        check("拖 4% 快甩 → 回弹", Swipe.decide(-40, -4000, W, 3, 10, FLING), 0);

        // 6) 边界：第一张不能再往右、最后一张不能再往左
        check("第一张右拖 → 回弹", Swipe.decide(400, 3000, W, 0, 10, FLING), 0);
        check("最后一张左拖 → 回弹", Swipe.decide(-400, -3000, W, 9, 10, FLING), 0);
        check("第一张左拖 → 下一张", Swipe.decide(-400, 0, W, 0, 10, FLING), 1);
        check("最后一张右拖 → 上一张", Swipe.decide(400, 0, W, 9, 10, FLING), -1);

        // 7) 只有一张 / 宽度异常
        check("只有一张 → 回弹", Swipe.decide(-900, -4000, W, 0, 1, FLING), 0);
        check("宽度为 0 → 回弹", Swipe.decide(-900, -4000, 0, 3, 10, FLING), 0);

        // 8) 阻尼：越界方向压缩，其余原值
        checkF("中间不阻尼", Swipe.damp(-200, 3, 10), -200, 0.01f);
        checkF("第一张右拖压缩", Swipe.damp(200, 0, 10), 70, 0.01f);
        checkF("第一张左拖不压缩", Swipe.damp(-200, 0, 10), -200, 0.01f);
        checkF("最后一张左拖压缩", Swipe.damp(-200, 9, 10), -70, 0.01f);
        checkF("最后一张右拖不压缩", Swipe.damp(200, 9, 10), 200, 0.01f);
        checkF("单张双向都压缩", Swipe.damp(200, 0, 1), 44, 0.01f);

        // 9) 吸附目标：往下一张就滑到 -W
        checkF("下一张目标位移", Swipe.target(1, W), -1000, 0.01f);
        checkF("上一张目标位移", Swipe.target(-1, W), 1000, 0.01f);

        // 10) 动画时长：随距离增长、有上限、不为负
        int d0 = Swipe.duration(0, W);
        int dHalf = Swipe.duration(500, W);
        int dFull = Swipe.duration(1000, W);
        int dOver = Swipe.duration(5000, W);
        checkB("时长随距离递增", d0 <= dHalf && dHalf <= dFull);
        checkB("时长有下限", d0 >= 100);
        checkB("时长有上限", dOver <= 320);
        checkB("超出屏宽也不越界", dOver == dFull);

        System.out.println(String.format("全部通过：%d 项", passed));
        if (failed > 0) {
            System.out.println(String.format("失败：%d 项", failed));
            System.exit(1);
        }
    }
}
