import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Popolog 런처 아이콘·로고. 강조색(주간 #2F5FC4) 둥근 사각형에 흰 'P', 그 아래로 운행이 쌓이는 점 세 개.
 * usage: java MakeIcon.java <out.png> [size]
 */
public class MakeIcon {
    public static void main(String[] a) throws Exception {
        int n = a.length > 1 ? Integer.parseInt(a[1]) : 192;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x2F5FC4));
        g.fill(new RoundRectangle2D.Double(0, 0, n, n, n * 0.46, n * 0.46));
        g.setColor(Color.WHITE);
        // P: 세로 기둥 + 둥근 고리 (글꼴에 기대지 않고 도형으로 그린다)
        double s = n / 192.0;
        float stroke = (float) (22 * s);
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(62 * s, 50 * s, 62 * s, 142 * s));
        Path2D bowl = new Path2D.Double();
        bowl.moveTo(62 * s, 50 * s);
        bowl.lineTo(100 * s, 50 * s);
        bowl.curveTo(140 * s, 50 * s, 140 * s, 112 * s, 100 * s, 112 * s);
        bowl.lineTo(62 * s, 112 * s);
        g.draw(bowl);
        // 기록이 쌓이는 점 (작게 → 크게)
        double[][] dots = {{104, 142, 9}, {126, 142, 11}, {150, 142, 13}};
        for (double[] d : dots) g.fill(new Ellipse2D.Double((d[0] - d[2] / 2) * s, (d[1] - d[2] / 2) * s, d[2] * s, d[2] * s));
        g.dispose();
        ImageIO.write(img, "png", new File(a[0]));
    }
}
