package press.mizhifei.dentist.clinicalrecords.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Configuration
@ConfigurationProperties(prefix = "file.upload")
public class FileUploadConfig {
    
    private long maxFileSize = 50 * 1024 * 1024; // 50MB
    private long maxRequestSize = 100 * 1024 * 1024; // 100MB
    private String[] allowedImageTypes = {"image/jpeg", "image/png", "image/tiff", "image/bmp"};
    private int thumbnailWidth = 200;
    private int thumbnailHeight = 200;
    

    
    // Getters and setters
    public long getMaxFileSize() { return maxFileSize; }
    public void setMaxFileSize(long maxFileSize) { this.maxFileSize = maxFileSize; }
    public long getMaxRequestSize() { return maxRequestSize; }
    public void setMaxRequestSize(long maxRequestSize) { this.maxRequestSize = maxRequestSize; }
    public String[] getAllowedImageTypes() { return allowedImageTypes.clone(); }
    public void setAllowedImageTypes(String[] allowedImageTypes) {
        this.allowedImageTypes = allowedImageTypes == null ? new String[0] : allowedImageTypes.clone();
    }
    public int getThumbnailWidth() { return thumbnailWidth; }
    public void setThumbnailWidth(int thumbnailWidth) { this.thumbnailWidth = thumbnailWidth; }
    public int getThumbnailHeight() { return thumbnailHeight; }
    public void setThumbnailHeight(int thumbnailHeight) { this.thumbnailHeight = thumbnailHeight; }
}
