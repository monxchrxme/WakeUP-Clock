import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.io.File;
import javax.imageio.ImageIO;

/** Decode an actual browser screenshot using the same ZXing core as the Android scanner. */
public class DecodeQr {
    public static void main(String[] args) throws Exception {
        var image = ImageIO.read(new File(args[0]));
        int w = image.getWidth(), h = image.getHeight();
        var source = new RGBLuminanceSource(w, h, image.getRGB(0, 0, w, h, null, 0, w));
        var result = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source)));
        var value = result.getText();
        if (!value.matches("wakeup://dismiss/v1/[a-f0-9]{16}/[0-9]+/[a-f0-9]{64}"))
            throw new IllegalStateException("Unexpected QR type");
        System.out.println("Browser screenshot QR decoded successfully by ZXing (dismiss/v1).");
    }
}
