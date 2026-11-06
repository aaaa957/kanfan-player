import com.kanfan.player.PdfLayout;

/** PDF 排版算术：页偏移、可视范围、缩放后的平移夹取 */
public class PdfLayoutTest {

    private static int passed = 0;
    private static int failed = 0;

    static void ok(String name, boolean v) {
        if (v) passed++;
        else {
            failed++;
            System.out.println("  ✗ " + name);
        }
    }

    static void eq(String name, Object a, Object b) {
        boolean same = (a == null) ? b == null : a.equals(b);
        if (same) passed++;
        else {
            failed++;
            System.out.println("  ✗ " + name + "  期望 " + b + " 实际 " + a);
        }
    }

    static void near(String name, float a, float b, float eps) {
        if (Math.abs(a - b) <= eps) passed++;
        else {
            failed++;
            System.out.println("  ✗ " + name + "  期望 " + b + " 实际 " + a);
        }
    }

    public static void main(String[] args) {
        // 三页 300x400，视口宽 300，页间距 10
        PdfLayout L = new PdfLayout(
                new int[]{300, 300, 300},
                new int[]{400, 400, 400},
                300f, 10f);
        eq("页数", L.count, 3);
        near("第 1 页顶部", L.topOf(0), 0f, 0.01f);
        near("第 2 页顶部", L.topOf(1), 410f, 0.01f);
        near("第 3 页顶部", L.topOf(2), 820f, 0.01f);
        near("总高", L.total, 1220f, 0.01f);
        near("页高", L.heightOf(0), 400f, 0.01f);

        // pageAt 的边界：页内、页间距、末尾
        eq("y=0", L.pageAt(0), 0);
        eq("y=399 仍在第 1 页", L.pageAt(399), 0);
        eq("y=410 进入第 2 页", L.pageAt(410), 1);
        eq("y=1229 最后一页", L.pageAt(1229), 2);
        eq("y 超出末尾夹到最后", L.pageAt(99999), 2);
        eq("y 为负夹到第一页", L.pageAt(-5), 0);
        eq("页间空隙归上一页", L.pageAt(405), 0);

        // 可视范围
        int[] v = L.visible(0, 500);
        eq("可视首页", v[0], 0);
        eq("可视末页", v[1], 1);
        int[] v2 = L.visible(700, 900);
        eq("中段首页", v2[0], 1);
        eq("中段末页", v2[1], 2);
        int[] v3 = L.visible(-100, -10);
        eq("全负也返回第一页", v3[0], 0);
        int[] v4 = L.visible(1200, 5000);
        eq("越界返回最后一页", v4[1], 2);

        // 跳页
        near("跳第 3 页", L.panForPage(2), 820f, 0.01f);

        // 平移夹取：视口 600，内容 1220 → 最多滚到 620
        near("负 pan 夹到 0", PdfLayout.clampPan(-5, 600, 1230, 1), 0f, 0.01f);
        near("超界夹到最大", PdfLayout.clampPan(700, 600, 1220, 1), 620f, 0.01f);
        near("内值不动", PdfLayout.clampPan(100, 600, 1220, 1), 100f, 0.01f);
        near("内容比视口短时归零", PdfLayout.clampPan(50, 800, 500, 1), 0f, 0.01f);
        // 放大 2 倍后可视内容变一半，最大滚动 1220-300=920
        near("zoom=2 的最大滚动", PdfLayout.clampPan(2000, 600, 1220, 2), 920f, 0.01f);

        // 以焦点为中心缩放：内容点 400 保持在焦点 300 下方
        near("缩放后的 pan", PdfLayout.zoomPan(100, 300, 1, 2), 250f, 0.01f);
        near("缩回 1 倍复原", PdfLayout.zoomPan(250, 300, 2, 1), 100f, 0.01f);

        // 位图宽度
        eq("1 倍渲染宽", PdfLayout.renderWidth(400, 1), 400);
        eq("3 倍渲染宽", PdfLayout.renderWidth(400, 3), 1200);
        eq("封顶 2400", PdfLayout.renderWidth(400, 10), 2400);
        eq("最小 320", PdfLayout.renderWidth(100, 1), 320);

        // 不同长宽比的页要各自算高
        PdfLayout mix = new PdfLayout(
                new int[]{600, 600},
                new int[]{800, 1200},
                300f, 0f);
        near("混合页高 1", mix.heightOf(0), 400f, 0.01f);
        near("混合页高 2", mix.heightOf(1), 600f, 0.01f);
        near("混合总高", mix.total, 1000f, 0.01f);

        // 空文档不能崩
        PdfLayout empty = new PdfLayout(new int[0], new int[0], 300, 10);
        eq("空文档页数", empty.count, 0);
        eq("空文档总高", empty.total, 0f);
        int[] ev = empty.visible(0, 100);
        eq("空文档可视首页", ev[0], 0);
        eq("空文档可视末页", ev[1], -1);

        // 宽高为 0 的坏页不能除零
        PdfLayout bad = new PdfLayout(new int[]{0, 300}, new int[]{0, 400}, 300, 0);
        ok("坏页高不是 NaN", !Float.isNaN(bad.heightOf(0)) && !Float.isInfinite(bad.heightOf(0)));
        near("正常页仍正确", bad.heightOf(1), 400f, 0.01f);

        // 传 null 也不能崩
        PdfLayout nul = new PdfLayout(null, null, 300, 10);
        eq("null 页数", nul.count, 0);

        System.out.println("PdfLayoutTest: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }
}
