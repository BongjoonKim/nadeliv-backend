package server.nadeliv.travel.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import server.nadeliv.error.CustomException;
import server.nadeliv.error.ErrorCode;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    private static final String BUCKET_NAME = "koraveler-travel";
    private static final String BASE_PATH = "travel-projects";

    public String uploadFile(MultipartFile file, String travelId) {
        validateMediaType(file);

        String originalFileName = file.getOriginalFilename();
        String extension = getExtension(originalFileName);
        String fileName = UUID.randomUUID() + extension;
        String key = BASE_PATH + "/" + travelId + "/origin/" + fileName;

        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            String fileUrl = "https://" + BUCKET_NAME + ".s3.ap-northeast-2.amazonaws.com/" + key;
            log.info("S3 upload success: {}", fileUrl);
            return fileUrl;
        } catch (IOException e) {
            log.error("S3 upload failed: {}", e.getMessage());
            throw new CustomException(ErrorCode.S3_UPLOAD_FAILED, e.getMessage());
        }
    }

    public ResponseInputStream<GetObjectResponse> downloadFile(String fileUrl) {
        try {
            String key = extractKeyFromUrl(fileUrl);
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .build();

            return s3Client.getObject(getObjectRequest);
        } catch (Exception e) {
            log.error("S3 download failed: {}", e.getMessage());
            throw new CustomException(ErrorCode.S3_DOWNLOAD_FAILED, e.getMessage());
        }
    }

    public void deleteFile(String fileUrl) {
        try {
            String key = extractKeyFromUrl(fileUrl);
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .build();

            s3Client.deleteObject(deleteObjectRequest);
            log.info("S3 delete success: {}", key);
        } catch (Exception e) {
            log.error("S3 delete failed: {}", e.getMessage());
        }
    }

    /**
     * S3 URL에서 버킷명과 key를 모두 추출해 삭제.
     * BUCKET_NAME 하드코딩에 의존하지 않으므로 haries-img/haries-thumbnail 등
     * 임의의 버킷에 있는 객체를 삭제할 때 사용.
     */
    public void deleteFileByUrl(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return;
        }
        try {
            S3UrlParts parts = parseS3Url(fileUrl);
            if (parts == null) {
                log.warn("S3 URL parse failed, skipping delete: {}", fileUrl);
                return;
            }
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(parts.bucket)
                    .key(parts.key)
                    .build();
            s3Client.deleteObject(deleteObjectRequest);
            log.info("S3 delete success: bucket={}, key={}", parts.bucket, parts.key);
        } catch (Exception e) {
            log.error("S3 deleteByUrl failed for {}: {}", fileUrl, e.getMessage());
        }
    }

    private record S3UrlParts(String bucket, String key) {}

    /**
     * 지원 형식:
     *  - https://{bucket}.s3.{region}.amazonaws.com/{key}
     *  - https://{bucket}.s3.amazonaws.com/{key}
     *  - https://s3.{region}.amazonaws.com/{bucket}/{key}
     */
    private S3UrlParts parseS3Url(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath() != null ? uri.getPath() : "";
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (host == null) return null;

            // virtual-hosted style: {bucket}.s3...
            int s3Idx = host.indexOf(".s3");
            if (s3Idx > 0) {
                String bucket = host.substring(0, s3Idx);
                if (path.isEmpty()) return null;
                return new S3UrlParts(bucket, path);
            }
            // path-style: s3.region.amazonaws.com/{bucket}/{key}
            if (host.startsWith("s3")) {
                int slash = path.indexOf('/');
                if (slash <= 0) return null;
                String bucket = path.substring(0, slash);
                String key = path.substring(slash + 1);
                return new S3UrlParts(bucket, key);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Presigned 직접 업로드 (웹·iOS 공용) ====================

    /** 원본 객체 key — Lambda 썸네일 규칙(/origin/ → /thumbnails/)을 따른다 */
    public String buildOriginKey(String travelId, String fileName) {
        return BASE_PATH + "/" + travelId + "/origin/" + fileName;
    }

    public String buildFileUrl(String key) {
        return "https://" + BUCKET_NAME + ".s3.ap-northeast-2.amazonaws.com/" + key;
    }

    /** 단일 PUT URL. Content-Type·Content-Length 가 서명에 포함되어 다른 크기/타입은 S3 가 거부한다 */
    public String presignPut(String key, String contentType, long contentLength, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(key)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();
        return s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .putObjectRequest(put)
                        .build())
                .url().toString();
    }

    public String createMultipartUpload(String key, String contentType) {
        try {
            return s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .contentType(contentType)
                    .build()).uploadId();
        } catch (Exception e) {
            log.error("S3 createMultipartUpload failed: {}", e.getMessage());
            throw new CustomException(ErrorCode.S3_UPLOAD_FAILED, e.getMessage());
        }
    }

    /** 멀티파트 part URL. part 크기도 서명에 포함 */
    public String presignUploadPart(String key, String uploadId, int partNumber, long partLength, Duration ttl) {
        UploadPartRequest part = UploadPartRequest.builder()
                .bucket(BUCKET_NAME)
                .key(key)
                .uploadId(uploadId)
                .partNumber(partNumber)
                .contentLength(partLength)
                .build();
        return s3Presigner.presignUploadPart(UploadPartPresignRequest.builder()
                        .signatureDuration(ttl)
                        .uploadPartRequest(part)
                        .build())
                .url().toString();
    }

    /** S3 에 실제 올라간 part 목록 (클라이언트가 ETag 를 보내지 않아도 서버가 직접 확인) */
    public List<CompletedPart> listUploadedParts(String key, String uploadId) {
        List<CompletedPart> parts = new ArrayList<>();
        Integer marker = null;
        while (true) {
            ListPartsResponse res = s3Client.listParts(ListPartsRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .uploadId(uploadId)
                    .partNumberMarker(marker)
                    .build());
            for (Part p : res.parts()) {
                parts.add(CompletedPart.builder().partNumber(p.partNumber()).eTag(p.eTag()).build());
            }
            if (!Boolean.TRUE.equals(res.isTruncated())) break;
            marker = res.nextPartNumberMarker();
        }
        return parts;
    }

    public void completeMultipartUpload(String key, String uploadId, List<CompletedPart> parts) {
        s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(BUCKET_NAME)
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                .build());
    }

    public void abortMultipartUpload(String key, String uploadId) {
        try {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .uploadId(uploadId)
                    .build());
        } catch (NoSuchUploadException e) {
            // 이미 완료·중단된 업로드
        } catch (Exception e) {
            log.warn("S3 abortMultipartUpload failed key={}: {}", key, e.getMessage());
        }
    }

    /** 객체 메타데이터. 없으면 null */
    public HeadObjectResponse headObject(String key) {
        try {
            return s3Client.headObject(HeadObjectRequest.builder().bucket(BUCKET_NAME).key(key).build());
        } catch (NoSuchKeyException e) {
            return null;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return null;
            throw e;
        }
    }

    public void deleteKey(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET_NAME).key(key).build());
        } catch (Exception e) {
            log.warn("S3 delete failed key={}: {}", key, e.getMessage());
        }
    }

    private void validateMediaType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || (!contentType.startsWith("image/") && !contentType.startsWith("video/"))) {
            throw new CustomException(ErrorCode.INVALID_MEDIA_TYPE);
        }
    }

    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf("."));
    }

    private static final List<String> VIDEO_EXTENSIONS = List.of(
            ".mp4", ".webm", ".mov", ".avi", ".mkv", ".quicktime"
    );

    /**
     * origin 파일 URL로부터 썸네일 URL을 생성
     * Lambda 규칙: /origin/ → /thumbnails/
     * - 이미지: 확장자 유지 (photo.jpg → photo.jpg)
     * - 비디오: 확장자 .jpg로 변경 (video.mov → video.jpg)
     */
    public String buildThumbnailUrl(String fileUrl) {
        if (fileUrl == null || !fileUrl.contains("/origin/")) {
            return null;
        }
        String thumbnailUrl = fileUrl.replace("/origin/", "/thumbnails/");
        if (isVideoFile(fileUrl)) {
            int lastDotIndex = thumbnailUrl.lastIndexOf(".");
            if (lastDotIndex > 0) {
                thumbnailUrl = thumbnailUrl.substring(0, lastDotIndex) + ".jpg";
            }
        }
        return thumbnailUrl;
    }

    private boolean isVideoFile(String url) {
        String lowerUrl = url.toLowerCase();
        return VIDEO_EXTENSIONS.stream().anyMatch(lowerUrl::endsWith);
    }

    /**
     * 라이트박스·뷰어용 중간 크기(2048px) JPEG URL. Lambda 규칙: /origin/ → /display/, 확장자 .jpg
     * HEIC 처럼 브라우저가 못 그리는 원본도 이 이미지로 보여준다. 영상은 없음(null).
     */
    public String buildDisplayUrl(String fileUrl) {
        if (fileUrl == null || !fileUrl.contains("/origin/") || isVideoFile(fileUrl)) {
            return null;
        }
        String displayUrl = fileUrl.replace("/origin/", "/display/");
        int lastDotIndex = displayUrl.lastIndexOf(".");
        if (lastDotIndex > displayUrl.lastIndexOf("/")) {
            displayUrl = displayUrl.substring(0, lastDotIndex);
        }
        return displayUrl + ".jpg";
    }

    /**
     * 브라우저가 S3 에서 바로 받도록 하는 presigned GET URL.
     * response-content-disposition 을 서명에 넣어 저장 파일명을 원본 이름으로 강제한다 (EC2 를 거치지 않음).
     */
    public String presignGet(String fileUrl, String downloadFileName, String contentType, Duration ttl) {
        String key = extractKeyFromUrl(fileUrl);
        String encodedName = java.net.URLEncoder.encode(
                downloadFileName != null ? downloadFileName : key.substring(key.lastIndexOf('/') + 1),
                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        GetObjectRequest.Builder get = GetObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(key)
                .responseContentDisposition("attachment; filename*=UTF-8''" + encodedName);
        if (contentType != null && !contentType.isBlank()) {
            get.responseContentType(contentType);
        }
        return s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(get.build())
                        .build())
                .url().toString();
    }

    private String extractKeyFromUrl(String fileUrl) {
        String prefix = "https://" + BUCKET_NAME + ".s3.ap-northeast-2.amazonaws.com/";
        if (fileUrl.startsWith(prefix)) {
            return fileUrl.substring(prefix.length());
        }
        return fileUrl;
    }
}
