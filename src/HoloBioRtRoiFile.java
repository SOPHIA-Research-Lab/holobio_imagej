import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared ROI text file for live / offline phase-profile comparison.
 *
 * <pre>
 * # comment
 * width 1280
 * height 960
 * line L1 180 480 1100 480
 * rect R1 520 360 240 160
 * </pre>
 */
final class HoloBioRtRoiFile {

    static final class Roi {
        final String name;
        final boolean rect;
        final double x1, y1, x2, y2; // rect: x1,y1 top-left; x2,y2 = width,height

        Roi(String name, boolean rect, double x1, double y1, double x2, double y2) {
            this.name = name; this.rect = rect;
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
        }
    }

    static final class Bundle {
        final int width, height;
        final List<Roi> rois;
        Bundle(int width, int height, List<Roi> rois) {
            this.width = width; this.height = height; this.rois = rois;
        }
    }

    private HoloBioRtRoiFile() {}

    static Bundle load(File file) throws IOException {
        int w = 0, h = 0;
        List<Roi> rois = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\s+");
                if (p[0].equalsIgnoreCase("width") && p.length >= 2) {
                    w = Integer.parseInt(p[1]);
                } else if (p[0].equalsIgnoreCase("height") && p.length >= 2) {
                    h = Integer.parseInt(p[1]);
                } else if (p[0].equalsIgnoreCase("line") && p.length >= 6) {
                    rois.add(new Roi(p[1], false,
                            Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                            Double.parseDouble(p[4]), Double.parseDouble(p[5])));
                } else if (p[0].equalsIgnoreCase("rect") && p.length >= 6) {
                    rois.add(new Roi(p[1], true,
                            Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                            Double.parseDouble(p[4]), Double.parseDouble(p[5])));
                }
            }
        }
        return new Bundle(w, h, rois);
    }

    static HoloBioRtDisplayPanel.ProfileLine toProfileLine(Roi r, Color color) {
        return toProfileLine(r, color, 1.0, 1.0);
    }

    /** Scale from declared ROI image size into the current reconstruction frame. */
    static HoloBioRtDisplayPanel.ProfileLine toProfileLine(Roi r, Color color,
                                                           double sx, double sy) {
        if (r.rect) {
            return HoloBioRtDisplayPanel.ProfileLine.rect(
                    r.x1 * sx, r.y1 * sy, r.x2 * sx, r.y2 * sy, color, r.name);
        }
        return HoloBioRtDisplayPanel.ProfileLine.line(
                r.x1 * sx, r.y1 * sy, r.x2 * sx, r.y2 * sy, color, r.name);
    }
}
