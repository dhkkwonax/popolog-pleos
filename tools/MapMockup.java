import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 에뮬레이터 Pleos 내비는 빈 지도 스타일(connect_empty)이라 지도 면이 비어 있다.
 * 시연 스크린샷(_mockup)용으로, 내비 창의 "빈 지도 면" 픽셀에만 OpenStreetMap 타일을 깐다.
 * 내비의 버튼·검색창 같은 UI 노드 영역과, 배경색이 아닌 픽셀(현재 위치 표시 등)은 그대로 둔다.
 *
 * usage: java MapMockup.java <in.png> <out.png> <nav.xml> <paneL,T,R,B> <tileDir> <zoom> <tileX> <tileY> <fontPath>
 *   tileX/tileY: 창 가운데에 올 지점의 타일 좌표(소수)
 */
public class MapMockup {
    public static void main(String[] a) throws Exception {
        BufferedImage img = ImageIO.read(new File(a[0]));
        String xml = Files.readString(new File(a[2]).toPath());
        String[] p = a[3].split(",");
        Rectangle pane = new Rectangle(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                Integer.parseInt(p[2]) - Integer.parseInt(p[0]), Integer.parseInt(p[3]) - Integer.parseInt(p[1]));
        File tileDir = new File(a[4]);
        int z = Integer.parseInt(a[5]);
        double tx = Double.parseDouble(a[6]), ty = Double.parseDouble(a[7]);

        // 내비 앱의 UI 노드(창 전체 크기의 지도 뷰는 제외)
        List<Rectangle> keep = new ArrayList<>();
        Matcher m = Pattern.compile("<node [^>]*package=\"ai\\.umos\\.maps[^\"]*\"[^>]*bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"").matcher(xml);
        while (m.find()) {
            Rectangle r = new Rectangle(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)) - Integer.parseInt(m.group(1)), Integer.parseInt(m.group(4)) - Integer.parseInt(m.group(2)));
            if (r.width <= 0 || r.height <= 0) continue;
            if ((long) r.width * r.height > (long) pane.width * pane.height * 0.5) continue;
            if (!pane.intersects(r)) continue;
            keep.add(r);
        }

        // 지도 그리기 (창 크기 캔버스)
        BufferedImage map = new BufferedImage(pane.width, pane.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = map.createGraphics();
        double originX = tx * 256 - pane.width / 2.0, originY = ty * 256 - pane.height / 2.0;
        for (int x0 = (int) Math.floor(originX / 256); x0 * 256 < originX + pane.width; x0++) {
            for (int y0 = (int) Math.floor(originY / 256); y0 * 256 < originY + pane.height; y0++) {
                File f = new File(tileDir, z + "-" + x0 + "-" + y0 + ".png");
                if (!f.exists()) continue;
                g.drawImage(ImageIO.read(f), (int) Math.round(x0 * 256 - originX), (int) Math.round(y0 * 256 - originY), null);
            }
        }
        g.dispose();

        // 빈 지도 면의 배경색: 창 안에서 UI 노드 밖 픽셀 중 가장 흔한 색
        java.util.Map<Integer, Integer> hist = new java.util.HashMap<>();
        for (int y = pane.y; y < pane.y + pane.height; y += 4)
            for (int x = pane.x; x < pane.x + pane.width; x += 4)
                if (!inside(keep, x, y)) hist.merge(img.getRGB(x, y) & 0xFFFFFF, 1, Integer::sum);
        int bg = hist.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).get().getKey();

        int replaced = 0;
        for (int y = pane.y; y < pane.y + pane.height; y++) {
            for (int x = pane.x; x < pane.x + pane.width; x++) {
                if (inside(keep, x, y)) continue;
                if (dist(img.getRGB(x, y) & 0xFFFFFF, bg) > 6) continue; // 실제로 그려진 것은 남긴다
                img.setRGB(x, y, map.getRGB(x - pane.x, y - pane.y));
                replaced++;
            }
        }

        // 표기: 지도 왼쪽 위 모서리에 작게 (아래쪽은 내비 검색창이 차지한다)
        Graphics2D t = img.createGraphics();
        t.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font font = Font.createFont(Font.TRUETYPE_FONT, new File(a[8])).deriveFont(16f);
        t.setFont(font);
        String note = "© OpenStreetMap contributors";
        FontMetrics fm = t.getFontMetrics();
        int bx = pane.x + 10, by = pane.y + 10;
        t.setColor(new Color(255, 255, 255, 210));
        t.fillRect(bx, by, fm.stringWidth(note) + 12, fm.getHeight() + 4);
        t.setColor(new Color(40, 40, 40));
        t.drawString(note, bx + 6, by + 2 + fm.getAscent());
        t.dispose();

        ImageIO.write(img, "png", new File(a[1]));
        System.out.printf("bg=%06x keep=%d replaced=%d%n", bg, keep.size(), replaced);
    }

    static boolean inside(List<Rectangle> rs, int x, int y) {
        for (Rectangle r : rs) if (r.contains(x, y)) return true;
        return false;
    }

    static int dist(int a, int b) {
        return Math.max(Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)),
                Math.max(Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)), Math.abs((a & 255) - (b & 255))));
    }
}
