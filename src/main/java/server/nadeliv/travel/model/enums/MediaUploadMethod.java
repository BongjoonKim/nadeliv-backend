package server.nadeliv.travel.model.enums;

public enum MediaUploadMethod {
    SINGLE,     // presigned PUT 1회
    MULTIPART   // S3 멀티파트 (part 별 presigned PUT)
}
