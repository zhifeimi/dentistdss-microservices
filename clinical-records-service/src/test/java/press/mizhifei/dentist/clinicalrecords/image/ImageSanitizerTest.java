package press.mizhifei.dentist.clinicalrecords.image;

import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;
import press.mizhifei.dentist.clinicalrecords.config.FileUploadConfig;
import press.mizhifei.dentist.clinicalrecords.exception.InvalidClinicalRequestException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DATA-03 evidence suite for the upload sanitizer: false MIME, polyglot,
 * oversized pixels, and corrupt bytes all fail; valid uploads come out as
 * canonical re-encoded artifacts with the payload destroyed by construction.
 */
class ImageSanitizerTest {

    private final ImageSanitizer sanitizer = new ImageSanitizer(new FileUploadConfig());

    @Test
    void validJpegProducesCanonicalJpegAndThumbnail() throws IOException {
        byte[] jpeg = encode(smallImage(32, 24), "jpg");

        SanitizedImage result = sanitizer.sanitize(upload(jpeg, "image/jpeg"));

        assertEquals("image/jpeg", result.canonicalContentType());
        assertEquals(32, result.width());
        assertEquals(24, result.height());
        assertNotNull(result.thumbnailBytes());
        // The canonical artifact decodes cleanly and keeps the dimensions.
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.canonicalBytes()));
        assertNotNull(decoded);
        assertEquals(32, decoded.getWidth());
        assertEquals(24, decoded.getHeight());
    }

    @Test
    void validTiffProducesCanonicalPng() throws IOException {
        byte[] tiff = encode(smallImage(16, 16), "tiff");

        SanitizedImage result = sanitizer.sanitize(upload(tiff, "image/tiff"));

        assertEquals("image/png", result.canonicalContentType());
        assertArrayEquals(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47},
                Arrays.copyOf(result.canonicalBytes(), 4));
    }

    @Test
    void validBmpProducesCanonicalPng() throws IOException {
        byte[] bmp = encode(smallImage(16, 16), "bmp");

        SanitizedImage result = sanitizer.sanitize(upload(bmp, "image/bmp"));

        assertEquals("image/png", result.canonicalContentType());
    }

    @Test
    void falseMimeDeclaredJpegButPngBytesIsRejected() throws IOException {
        byte[] png = encode(smallImage(8, 8), "png");

        assertThrows(InvalidClinicalRequestException.class,
                () -> sanitizer.sanitize(upload(png, "image/jpeg")));
    }

    @Test
    void falseMimeDeclaredPngButJpegBytesIsRejected() throws IOException {
        byte[] jpeg = encode(smallImage(8, 8), "jpg");

        assertThrows(InvalidClinicalRequestException.class,
                () -> sanitizer.sanitize(upload(jpeg, "image/png")));
    }

    @Test
    void nonImageBytesAreRejected() {
        byte[] pdf = "%PDF-1.4 not an image at all".getBytes();

        assertThrows(InvalidClinicalRequestException.class,
                () -> sanitizer.sanitize(upload(pdf, "image/png")));
    }

    @Test
    void disallowedDeclaredTypeIsRejectedEvenWithValidBytes() throws IOException {
        byte[] jpeg = encode(smallImage(8, 8), "jpg");

        assertThrows(InvalidClinicalRequestException.class,
                () -> sanitizer.sanitize(upload(jpeg, "image/gif")));
    }

    @Test
    void polyglotPayloadDoesNotSurviveReencode() throws IOException {
        byte[] png = encode(smallImage(16, 16), "png");
        byte[] payload = "PK\3\4polyglot-payload-marker".getBytes();
        byte[] polyglot = new byte[png.length + payload.length];
        System.arraycopy(png, 0, polyglot, 0, png.length);
        System.arraycopy(payload, 0, polyglot, png.length, payload.length);

        SanitizedImage result = sanitizer.sanitize(upload(polyglot, "image/png"));

        // The upload is a valid image, so it is accepted — but the canonical
        // artifact is a decode+re-encode, so the appended payload is gone.
        assertNull(findSubsequence(result.canonicalBytes(), payload),
                "appended polyglot payload must not survive canonical re-encode");
        assertNotNull(ImageIO.read(new ByteArrayInputStream(result.canonicalBytes())),
                "canonical artifact must remain a decodable image");
    }

    @Test
    void oversizedPixelHeaderIsRejectedBeforeDecode() {
        // A complete-looking PNG header advertising 100000x100000 pixels;
        // the guard must reject from the header without decoding pixels.
        byte[] giant = pngHeader(100000, 100000);

        assertThrows(InvalidClinicalRequestException.class,
                () -> sanitizer.sanitize(upload(giant, "image/png")));
    }

    @Test
    void oversizedSingleDimensionIsRejected() {
        byte[] wide = pngHeader(20000, 10);

        assertThrows(InvalidClinicalRequestException.class,
                () -> sanitizer.sanitize(upload(wide, "image/png")));
    }

    @Test
    void truncatedImageIsRejected() throws IOException {
        byte[] png = encode(smallImage(16, 16), "png");
        byte[] truncated = Arrays.copyOf(png, Math.min(30, png.length));

        assertThrows(InvalidClinicalRequestException.class,
                () -> sanitizer.sanitize(upload(truncated, "image/png")));
    }

    @Test
    void emptyAndOversizedFilesAreRejected() throws IOException {
        MultipartFile empty = upload(new byte[0], "image/png");
        assertThrows(InvalidClinicalRequestException.class, () -> sanitizer.sanitize(empty));

        byte[] png = encode(smallImage(4, 4), "png");
        FileUploadConfig tight = new FileUploadConfig();
        tight.setMaxFileSize(8);
        ImageSanitizer tightSanitizer = new ImageSanitizer(tight);
        assertThrows(InvalidClinicalRequestException.class,
                () -> tightSanitizer.sanitize(upload(png, "image/png")));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static MultipartFile upload(byte[] bytes, String contentType) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(bytes.length == 0);
        when(file.getSize()).thenReturn((long) bytes.length);
        when(file.getContentType()).thenReturn(contentType);
        try {
            when(file.getBytes()).thenReturn(bytes);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return file;
    }

    private static BufferedImage smallImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, ((x * 255 / Math.max(1, width)) << 16) | (y * 255 / Math.max(1, height)));
            }
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, format, output), "no ImageIO writer for " + format);
            return output.toByteArray();
        }
    }

    /**
     * A minimal PNG: signature + IHDR chunk (width/height big-endian) with a
     * correct CRC, then garbage. Readable by an ImageReader's header parse,
     * rejected before any IDAT is decoded.
     */
    private static byte[] pngHeader(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN);
        ihdr.putInt(width).putInt(height);
        ihdr.put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        byte[] ihdrData = ihdr.array();

        ByteBuffer png = ByteBuffer.allocate(8 + 12 + 13 + 16).order(ByteOrder.BIG_ENDIAN);
        png.put(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        png.putInt(13);
        png.put("IHDR".getBytes());
        png.put(ihdrData);
        png.putInt(crc32("IHDR", ihdrData));
        png.put(new byte[16]); // bogus trailing bytes; never reached by the guard
        return png.array();
    }

    private static int crc32(String type, byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(type.getBytes());
        crc.update(data);
        return (int) crc.getValue();
    }

    private static Integer findSubsequence(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return null;
        }
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return null;
    }
}
