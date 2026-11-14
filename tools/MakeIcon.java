import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Popolog 런처 아이콘·로고. 강조색(주간 #2F5FC4) 둥근 사각형에 운행기록 종이 한 장과 출발→도착 경로.
 * (이전의 'P' 는 주차장 표시·Pleos 아이콘과 비슷해 보여 바꿨다)
 * usage: java MakeIcon.java <out.png> [size] [바탕색 #hex] [경로색 #hex] [기록줄색 #hex] [아래 띠 글자]
 */
public class MakeIcon {
    public static void main(String[] a) throws Exception {
        int n = a.length > 1 ? Integer.parseInt(a[1]) : 192;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Color bg = a.length > 2 ? Color.decode(a[2]) : new Color(0x2F5FC4);
        Color routeColor = a.length > 3 ? Color.decode(a[3]) : bg;
        Color lineColor = a.length > 4 ? Color.decode(a[4]) : new Color(0xB9CAF0);
        g.setColor(bg);
        g.fill(new RoundRectangle2D.Double(0, 0, n, n, n * 0.46, n * 0.46));
        double s = n / 192.0;
        // 운행기록 한 장: 흰 종이 위에 출발점에서 도착점까지 이어지는 경로. 글자(P)나 주차 표시를 쓰지 않는다
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(48 * s, 34 * s, 96 * s, 124 * s, 18 * s, 18 * s));
        Color blue = routeColor;
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
        g.setColor(lineColor);
        g.setStroke(new BasicStroke((float) (7 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(104 * s, 132 * s, 128 * s, 132 * s));
        g.draw(new Line2D.Double(104 * s, 146 * s, 120 * s, 146 * s));
        if (a.length > 5 && !a[5].isEmpty()) {
            // 데모용 표시: 아이콘 아래쪽에 띠를 두르고 글자를 넣는다 (둥근 사각형 밖으로 나가지 않게 자른다)
            g.setClip(new RoundRectangle2D.Double(0, 0, n, n, n * 0.46, n * 0.46));
            g.setColor(new Color(0xFFC107));
            g.fill(new Rectangle2D.Double(0, n * 0.79, n, n * 0.21));
            g.setColor(new Color(0x1F2430));
            g.setFont(new Font("Noto Sans CJK KR", Font.BOLD, (int) (n * 0.15)));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(a[5], (n - fm.stringWidth(a[5])) / 2f, (float) (n * 0.79 + (n * 0.21 + fm.getAscent() - fm.getDescent()) / 2));
            g.setClip(null);
        }
        g.dispose();
        ImageIO.write(img, "png", new File(a[0]));
    }
}
