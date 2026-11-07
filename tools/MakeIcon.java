import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Popolog 런처 아이콘·로고. 강조색(주간 #2F5FC4) 둥근 사각형에 운행기록 종이 한 장과 출발→도착 경로.
 * (이전의 'P' 는 주차장 표시·Pleos 아이콘과 비슷해 보여 바꿨다)
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
        double s = n / 192.0;
        // 운행기록 한 장: 흰 종이 위에 출발점에서 도착점까지 이어지는 경로. 글자(P)나 주차 표시를 쓰지 않는다
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(48 * s, 34 * s, 96 * s, 124 * s, 18 * s, 18 * s));
        Color blue = new Color(0x2F5FC4);
        g.setColor(blue);
        // 경로: 왼쪽 아래 출발점 → 구불구불 → 오른쪽 위 도착점
        g.setStroke(new BasicStroke((float) (9 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D route = new Path2D.Double();
        route.moveTo(76 * s, 132 * s);
        route.curveTo(76 * s, 104 * s, 118 * s, 118 * s, 118 * s, 92 * s);
        route.curveTo(118 * s, 72 * s, 86 * s, 78 * s, 94 * s, 60 * s);
        g.draw(route);
        // 출발점: 속이 빈 원, 도착점: 채운 원
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(64 * s, 120 * s, 24 * s, 24 * s));
        g.setColor(blue);
        g.setStroke(new BasicStroke((float) (8 * s)));
        g.draw(new Ellipse2D.Double(66 * s, 122 * s, 20 * s, 20 * s));
        g.fill(new Ellipse2D.Double(82 * s, 46 * s, 24 * s, 24 * s));
        // 기록 줄(운행기록부 느낌): 종이 오른쪽 아래의 짧은 줄 두 개
        g.setColor(new Color(0xB9CAF0));
        g.setStroke(new BasicStroke((float) (7 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(104 * s, 132 * s, 128 * s, 132 * s));
        g.draw(new Line2D.Double(104 * s, 146 * s, 120 * s, 146 * s));
        g.dispose();
        ImageIO.write(img, "png", new File(a[0]));
    }
}
