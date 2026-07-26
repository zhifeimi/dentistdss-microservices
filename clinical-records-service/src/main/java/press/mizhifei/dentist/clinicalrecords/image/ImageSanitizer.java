package press.mizhifei.dentist.clinicalrecords.image;

import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import press.mizhifei.dentist.clinicalrecords.config.FileUploadConfig;
import press.mizhifei.dentist.clinicalrecords.exception.InvalidClinicalRequestException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;

/**
 * DATA-03 upload sanitizer: turns an untrusted upload into a canonical
 * image artifact with a single decode.
 *
 * <p>
 * Pipeline: magic-byte sniffing (the client-declared content type is never
 * trusted and a declared/sniffed mismatch is rejected) → decompression-bomb
 * guard (dimensions are read from the image header BEFORE any pixel data is
 * decoded) → decode → canonical re-encode (which strips appended polyglot
 * payloads, comments, and EXIF/metadata by construction) → thumbnail from
 * the already-decoded pixels (no second decode, no second bomb surface).
 *
 * <p>
 * Canonical output is JPEG (family JPEG, quality 0.9) or PNG (families
 * PNG/TIFF/BMP). Every rejection throws {@link InvalidClinicalRequestException}
 * so a hostile upload maps to a generic 400 with no detail leak.
 */
@Component
public class ImageSanitizer {

    private static final String JPEG = "image/jpeg";
    private static final String PNG = "image/png";
    private static final String TIFF = "image/tiff";
    private static final String BMP = "image/bmp";

    private enum Family {
        JPEG_F(JPEG), PNG_F(PNG), TIFF_F(TIFF), BMP_F(BMP);

        private final String contentType;

        Family(String contentType) {
            this.contentType = contentType;
        }
    }

    private final FileUploadConfig config;

    public ImageSanitizer(FileUploadConfig config) {
        this.config = config;
    }

    public SanitizedImage sanitize(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > config.getMaxFileSize()) {
            throw new InvalidClinicalRequestException();
        }
        byte[] bytes = readBytes(file);
        Family family = sniff(bytes);
        if (family == null) {
            throw new InvalidClinicalRequestException();
        }
        requireDeclaredTypeMatches(file.getContentType(), family);

        BufferedImage decoded = decodeWithPixelGuard(bytes);
        byte[] canonical = reencode(decoded, family);
        String canonicalType = family == Family.JPEG_F ? JPEG : PNG;
        return new SanitizedImage(
                canonical,
                canonicalType,
                renderThumbnail(decoded),
                decoded.getWidth(),
                decoded.getHeight());
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new InvalidClinicalRequestException();
        }
    }

    /** Magic-byte family detection; {@code null} when the bytes match no allowed family. */
    private Family sniff(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return Family.JPEG_F;
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
            return Family.PNG_F;
        }
        if (bytes.length >= 4
                && ((bytes[0] == 0x49 && bytes[1] == 0x49 && bytes[2] == 0x2A && bytes[3] == 0x00)
                || (bytes[0] == 0x4D && bytes[1] == 0x4D && bytes[2] == 0x00 && bytes[3] == 0x2A))) {
            return Family.TIFF_F;
        }
        if (bytes.length >= 2 && bytes[0] == 0x42 && bytes[1] == 0x4D) {
            return Family.BMP_F;
        }
        return null;
    }

    /**
     * The declared type must be an allowed type AND agree with the sniffed
     * family — a false declaration fails the upload even when the bytes are a
     * valid image of another allowed family.
     */
    private void requireDeclaredTypeMatches(String declared, Family family) {
        if (declared == null
                || !Arrays.asList(config.getAllowedImageTypes()).contains(declared)
                || !declared.equals(family.contentType)) {
            throw new InvalidClinicalRequestException();
        }
    }

    /**
     * Reads dimensions from the image header first and rejects oversize
     * images before any pixel data is decoded (decompression-bomb guard).
     */
    private BufferedImage decodeWithPixelGuard(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new InvalidClinicalRequestException();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new InvalidClinicalRequestException();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > config.getMaxDimension()
                        || height > config.getMaxDimension()
                        || (long) width * (long) height > config.getMaxPixels()) {
                    throw new InvalidClinicalRequestException();
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof InvalidClinicalRequestException invalid) {
                throw invalid;
            }
            // Corrupt, truncated, or unsupported-variant image bytes.
            throw new InvalidClinicalRequestException();
        }
    }

    /**
     * Canonical re-encode. Because the bytes are produced by decoding and
     * re-encoding, any appended archive/script payload, comment, or metadata
     * block in the original simply does not exist in the output — the
     * polyglot is destroyed by construction.
     */
    private byte[] reencode(BufferedImage image, Family family) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (family == Family.JPEG_F) {
                Thumbnails.of(image)
                        .scale(1.0)
                        .outputFormat("jpg")
                        .outputQuality(0.9)
                        .toOutputStream(output);
            } else {
                Thumbnails.of(image)
                        .scale(1.0)
                        .outputFormat("png")
                        .toOutputStream(output);
            }
            return output.toByteArray();
        } catch (IOException | RuntimeException exception) {
            throw new InvalidClinicalRequestException();
        }
    }

    /** Thumbnail from the already-decoded pixels; failure is tolerated (null). */
    private byte[] renderThumbnail(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Thumbnails.of(image)
                    .size(config.getThumbnailWidth(), config.getThumbnailHeight())
                    .outputFormat("jpg")
                    .outputQuality(0.8)
                    .toOutputStream(output);
            return output.toByteArray();
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }
}
