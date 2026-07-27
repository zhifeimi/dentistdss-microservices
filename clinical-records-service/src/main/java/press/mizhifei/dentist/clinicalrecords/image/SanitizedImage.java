package press.mizhifei.dentist.clinicalrecords.image;

/**
 * The result of sanitizing an uploaded image (DATA-03): the canonical
 * re-encoded artifact and the thumbnail derived from the same single decode.
 * Byte arrays are defensively copied on construction and on access so the
 * canonical artifact cannot be mutated by either side.
 *
 * @param canonicalBytes       re-encoded image bytes (polyglot payloads,
 *                             comments, and metadata are stripped by the
 *                             decode + re-encode)
 * @param canonicalContentType always {@code image/jpeg} or {@code image/png}
 * @param thumbnailBytes       JPEG thumbnail, or an EMPTY array when
 *                             thumbnail rendering failed (tolerated)
 * @param width                decoded pixel width
 * @param height               decoded pixel height
 */
public record SanitizedImage(
        byte[] canonicalBytes,
        String canonicalContentType,
        byte[] thumbnailBytes,
        int width,
        int height) {

    public SanitizedImage {
        canonicalBytes = canonicalBytes.clone();
        thumbnailBytes = thumbnailBytes.clone();
    }

    @Override
    public byte[] canonicalBytes() {
        return canonicalBytes.clone();
    }

    @Override
    public byte[] thumbnailBytes() {
        return thumbnailBytes.clone();
    }
}
