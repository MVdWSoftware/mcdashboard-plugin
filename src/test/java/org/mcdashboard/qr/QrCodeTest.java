package org.mcdashboard.qr;

import static org.junit.jupiter.api.Assertions.*;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The encoder is checked against an independent decoder (ZXing), not against itself. */
class QrCodeTest {
    static String decode(QrCode qr) throws Exception {
        int scale = 6;
        int quiet = 4;
        int dim = (qr.size() + 2 * quiet) * scale;
        BufferedImage img = new BufferedImage(dim, dim, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < dim; y++) {
            for (int x = 0; x < dim; x++) {
                boolean dark = qr.dark(x / scale - quiet, y / scale - quiet);
                img.setRGB(x, y, dark ? 0x000000 : 0xFFFFFF);
            }
        }
        var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img)));
        return new MultiFormatReader().decode(bitmap, Map.of(DecodeHintType.PURE_BARCODE, Boolean.TRUE)).getText();
    }

    @Test
    void decodesAPairingLink() throws Exception {
        String link = "mcdashboard://connect?url=https%3A%2F%2Fmc.example.com&code=ABCD2345";
        QrCode qr = QrCode.encode(link);
        assertEquals(link, decode(qr));
    }

    @Test
    void decodesEveryVersionFromOneToTen() throws Exception {
        Random rnd = new Random(7);
        int[] lengths = {5, 14, 26, 42, 62, 84, 106, 122, 152, 180, 213};
        boolean[] seen = new boolean[11];
        for (int len : lengths) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) sb.append((char) ('a' + rnd.nextInt(26)));
            QrCode qr = QrCode.encode(sb.toString());
            seen[qr.version()] = true;
            assertEquals(sb.toString(), decode(qr), "length " + len + " (version " + qr.version() + ")");
        }
        for (int v = 1; v <= 10; v++) assertTrue(seen[v], "version " + v + " was not exercised");
    }

    @Test
    void decodesNonAscii() throws Exception {
        assertEquals("héllo wörld ✓", decode(QrCode.encode("héllo wörld ✓")));
    }

    @Test
    void refusesTextThatDoesNotFit() {
        assertThrows(IllegalArgumentException.class, () -> QrCode.encode("x".repeat(214)));
    }

    @Test
    void darkIsFalseOutsideTheCode() {
        QrCode qr = QrCode.encode("a");
        assertFalse(qr.dark(-1, 0));
        assertFalse(qr.dark(0, qr.size()));
        assertTrue(qr.dark(0, 0)); // finder corner
    }
}
