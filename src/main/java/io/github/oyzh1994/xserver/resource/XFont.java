package io.github.oyzh1994.xserver.resource;

import io.github.oyzh1994.xserver.client.ClientConnection;

import java.awt.Canvas;
import java.awt.Font;
import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A font resource.
 *
 * <p>X has its own font system, but rasterizing X font files is not worth reimplementing here:
 * each X font name is mapped onto a Java font and glyph metrics are derived from Java's font
 * machinery. Core protocol text drawing, {@code QueryFont} and {@code QueryTextExtents} are all
 * answered from this.
 */
public final class XFont extends XResource {

    /** Glyphs are described for byte values 0..255, the range core text requests can address. */
    public static final int MIN_CHAR = 0;
    public static final int MAX_CHAR = 255;

    private final String name;
    private final Font awtFont;
    private final FontMetrics metrics;
    private final int[] charWidths = new int[MAX_CHAR + 1];

    private int fontAscent;
    private int fontDescent;
    private int minLeftBearing;
    private int maxRightBearing;
    private int minWidth = Integer.MAX_VALUE;
    private int maxWidth;
    private boolean fixedPitch;

    public XFont(int id, ClientConnection owner, String name) {
        super(id, CLASS_FONT, owner);
        this.name = name;
        this.awtFont = resolveFont(name);
        this.metrics = new Canvas().getFontMetrics(awtFont);
        computeMetrics();
    }

    /**
     * Maps an X font name onto a Java font. Both short aliases ("fixed", "9x15") and long XLFD
     * names ("-misc-fixed-medium-r-normal--13-120-75-75-c-70-iso10646-1") are understood.
     */
    public static Font resolveFont(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int size = parsePixelSize(lower);
        String family = Font.SANS_SERIF;
        if (lower.equals("fixed") || lower.contains("fixed") || lower.contains("courier")
                || lower.contains("mono") || lower.contains("terminal") || lower.contains("console")) {
            family = Font.MONOSPACED;
        } else if (lower.contains("times") || (lower.contains("serif") && !lower.contains("sans"))) {
            family = Font.SERIF;
        }
        if (size <= 0 && lower.matches("\\d+x\\d+")) {
            // Names like 8x13 or 6x13 encode the pixel size in the second number.
            size = Integer.parseInt(lower.substring(lower.indexOf('x') + 1));
        }
        if (size <= 0) {
            size = 12;
        }
        int style = Font.PLAIN;
        if (lower.contains("bold")) {
            style |= Font.BOLD;
        }
        if (lower.contains("-i-") || lower.contains("italic") || lower.contains("oblique")) {
            style |= Font.ITALIC;
        }
        return new Font(family, style, size);
    }

    private static int parsePixelSize(String lower) {
        if (!lower.startsWith("-")) {
            return -1;
        }
        // XLFD: -foundry-family-weight-slant-setwidth-addstyle-pixelsize-...
        String[] parts = lower.split("-");
        if (parts.length > 7) {
            try {
                int value = Integer.parseInt(parts[7]);
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // not a sized XLFD, fall through
            }
        }
        return -1;
    }

    private void computeMetrics() {
        fontAscent = metrics.getAscent();
        fontDescent = metrics.getDescent();
        int minW = Integer.MAX_VALUE;
        int maxW = 0;
        for (int ch = MIN_CHAR; ch <= MAX_CHAR; ch++) {
            int width = metrics.charWidth((char) ch);
            if (width <= 0 && Character.isISOControl(ch)) {
                // Control characters have no glyph; X still reports the space width for them.
                width = metrics.charWidth(' ');
            }
            charWidths[ch] = width;
            minW = Math.min(minW, width);
            maxW = Math.max(maxW, width);
        }
        if (minW == Integer.MAX_VALUE) {
            minW = 0;
        }
        minWidth = minW;
        maxWidth = maxW;
        fixedPitch = minW == maxW;
        minLeftBearing = 0;
        maxRightBearing = maxW;
    }

    public String fontName() {
        return name;
    }

    public Font awtFont() {
        return awtFont;
    }

    public FontMetrics fontMetrics() {
        return metrics;
    }

    public int charWidth(int ch) {
        if (ch < MIN_CHAR || ch > MAX_CHAR) {
            return metrics.charWidth((char) ch);
        }
        return charWidths[ch];
    }

    /** Width of a Latin-1 byte string, which is what {@code PolyText8} measures. */
    public int stringWidth8(byte[] chars, int offset, int length) {
        int total = 0;
        for (int i = 0; i < length; i++) {
            total += charWidth(chars[offset + i] & 0xFF);
        }
        return total;
    }

    /** Width of a 16-bit character string, which is what {@code PolyText16} measures. */
    public int stringWidth16(char[] chars, int offset, int length) {
        int total = 0;
        for (int i = 0; i < length; i++) {
            total += charWidth(chars[offset + i]);
        }
        return total;
    }

    public int ascent() {
        return fontAscent;
    }

    public int descent() {
        return fontDescent;
    }

    public int minWidth() {
        return minWidth;
    }

    public int maxWidth() {
        return maxWidth;
    }

    public int minLeftBearing() {
        return minLeftBearing;
    }

    public int maxRightBearing() {
        return maxRightBearing;
    }

    public boolean isFixedPitch() {
        return fixedPitch;
    }

    /**
     * The numeric property set reported by {@code QueryFont}. String valued properties such as
     * FOUNDRY and FAMILY_NAME are reported separately, because their value is the atom of the
     * string rather than a number, and this class has no access to the atom table.
     */
    public List<FontProperty> properties() {
        List<FontProperty> properties = new ArrayList<>();
        properties.add(new FontProperty("FONT_ASCENT", fontAscent));
        properties.add(new FontProperty("FONT_DESCENT", fontDescent));
        properties.add(new FontProperty("DEFAULT_CHAR", '?'));
        properties.add(new FontProperty("PIXEL_SIZE", awtFont.getSize()));
        properties.add(new FontProperty("POINT_SIZE", awtFont.getSize() * 10));
        properties.add(new FontProperty("RESOLUTION_X", 100));
        properties.add(new FontProperty("RESOLUTION_Y", 100));
        properties.add(new FontProperty("SPACING", fixedPitch ? 2 : 0));
        properties.add(new FontProperty("AVERAGE_WIDTH", (minWidth + maxWidth) / 2 * 10));
        return properties;
    }

    /** Properties whose value is a string; the caller interns each one as an atom. */
    public List<TextProperty> textProperties() {
        List<TextProperty> properties = new ArrayList<>();
        properties.add(new TextProperty("FAMILY_NAME", familyName()));
        properties.add(new TextProperty("FOUNDRY", "xserver"));
        return properties;
    }

    private String familyName() {
        String family = awtFont.getFamily();
        // X family names are lower case.
        return family == null ? "unknown" : family.toLowerCase(Locale.ROOT);
    }

    /** A font property as reported by {@code QueryFont}: an atom name and a numeric value. */
    public record FontProperty(String name, int value) {
    }

    /** A font property whose value is a string, carried as the atom of that string. */
    public record TextProperty(String name, String value) {
    }

    @Override
    public String typeName() {
        return "font";
    }
}
