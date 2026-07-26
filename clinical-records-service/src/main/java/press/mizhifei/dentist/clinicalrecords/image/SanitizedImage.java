package press.mizhifei.dentist.clinicalrecords.image;

/**
 * The result of sanitizing an uploaded image (DATA-03): the canonical
 * re-encoded artifact and the thumbnail derived from the same single decode.
 *
 * @param canonicalBytes       re-encoded image bytes (polyglot payloads,
 *                             comments, and metadata are stripped by the
 *                             decode + re-encode)
 * @param canonicalContentType always {@code image/jpeg} or {@code image/png}
 * @param thumbnailBytes       JPEG thumbnail, or {@code null} when thumbnail
 *                             rendering failed (tolerated, as before)
 * @param width                decoded pixel width
 * @param height               decoded pixel height
 */
public record SanitizedImage(
        byte[] canonicalBytes,
        String canonicalContentType,
        byte[] thumbnailBytes,
        int width,
        int height) {
}
