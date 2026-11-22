package com.example.mealcheck.service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;

/** A bounded, conservative check for effectively black uploads before any model call. */
final class ImageQualityGuard {
    private ImageQualityGuard() {}

    static void rejectNearBlack(ValidatedImage image) {
        try (var bytes = image.openStream(); var input = new MemoryCacheImageInputStream(bytes)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("No image reader");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                var params = reader.getDefaultReadParam();
                params.setSourceSubsampling(Math.max(1, (reader.getWidth(0) + 127) / 128),
                        Math.max(1, (reader.getHeight(0) + 127) / 128), 0, 0);
                BufferedImage sample = reader.read(0, params);
                if (sample == null) throw new IOException("Empty image");
                int dark = 0, peak = 0, count = sample.getWidth() * sample.getHeight();
                for (int y = 0; y < sample.getHeight(); y++) for (int x = 0; x < sample.getWidth(); x++) {
                    int rgb = sample.getRGB(x, y);
                    int channel = Math.max((rgb >>> 16) & 255, Math.max((rgb >>> 8) & 255, rgb & 255));
                    if (channel <= 8) dark++;
                    peak = Math.max(peak, channel);
                }
                // Textured low-light photos should reach the model, not a broad brightness cutoff.
                if (peak <= 16 && dark >= Math.ceil(count * 0.999)) throw RecognitionFailureException.unclear();
            } finally { reader.dispose(); }
        } catch (RecognitionFailureException unclear) {
            throw unclear;
        } catch (IOException | RuntimeException invalid) {
            throw new AiChatClient.AiClientException("图片内容无效，请重新上传。", invalid);
        }
    }
}
