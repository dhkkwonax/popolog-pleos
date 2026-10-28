import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * GPU 모드 Windows 에뮬레이터에서는 창을 나눈 뒤 옆 칸 내비가 다시 그려지지 않아 검게 남는 일이 있다.
 * 내비 칸은 어차피 지도를 합성하는 자리라, 같은 촬영에서 정상으로 그려진 기준 장면의 내비 칸을 그대로 옮겨 붙인다.
 * 내비 칸이 거의 검을 때(어두운 픽셀 50% 이상)만 바꾸고, 차곡 창과 왼쪽 시스템 UI 는 건드리지 않는다.
 *
 * 판단은 합성 전 원본(raw)으로 하고, 바꾸는 것은 합성본(out)이다 (합성본은 검은 칸 위에 지도가 깔려 판단이 어렵다)
 * usage: java NavPanePaste.java <reference_mockup.png> <raw.png> <out_mockup.png> <L,T,R,B>
 */
public class NavPanePaste {
    public static void main(String[] a) throws Exception {
        BufferedImage ref = ImageIO.read(new File(a[0]));
        BufferedImage raw = ImageIO.read(new File(a[1]));
        File tf = new File(a[2]);
        BufferedImage img = ImageIO.read(tf);
        String[] b = a[3].split(",");
        int l = Integer.parseInt(b[0]), t = Integer.parseInt(b[1]), r = Integer.parseInt(b[2]), btm = Integer.parseInt(b[3]);
        if (darkShare(raw, l, t, r, btm) < 0.5) { System.out.println("ok " + tf.getName()); return; }
        if (darkShare(ref, l, t, r, btm) >= 0.5) { System.out.println("reference is dark, skip " + tf.getName()); return; }
        for (int y = t; y < btm; y++) for (int x = l; x < r; x++) img.setRGB(x, y, ref.getRGB(x, y));
        ImageIO.write(img, "png", tf);
        System.out.println("pasted " + tf.getName());
    }

    static double darkShare(BufferedImage img, int l, int t, int r, int b) {
        int dark = 0, n = 0;
        for (int y = t; y < b; y += 8) for (int x = l; x < r; x += 8) {
            int p = img.getRGB(x, y);
            n++;
            if (((p >> 16) & 255) < 15 && ((p >> 8) & 255) < 15 && (p & 255) < 15) dark++;
        }
        return n == 0 ? 0 : (double) dark / n;
    }
}
