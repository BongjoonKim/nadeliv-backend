package server.nadeliv.error;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ============= 인증 관련 에러 (AUTH) =============
    ACCESSTOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_001", "AccessToken expired"),
    REFESHTOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_002", "RefreshToken expired"),
    ACCESSTOKEN_NULL(HttpStatus.NOT_FOUND, "AUTH_003", "AccessToken is null"),

    // ============= 사용자 관련 에러 (USER) =============
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_001", "User not found"),
    USER_ALREADY_EXISTS(HttpStatus.CONFLICT, "USER_002", "User already exists"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "USER_003", "Invalid credentials"),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "USER_004", "Current password does not match"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "USER_005", "Email is already in use"),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "USER_006", "Account has been disabled"),
    EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "USER_007", "이메일 인증이 필요합니다"),
    EMAIL_SEND_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "USER_008", "일일 인증 코드 발송 횟수(5회)를 초과했습니다"),
    EMAIL_VERIFY_ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "USER_009", "인증 시도 횟수를 초과했습니다"),
    EMAIL_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "USER_010", "인증 코드가 만료되었거나 존재하지 않습니다"),
    EMAIL_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "USER_011", "인증 코드가 일치하지 않습니다"),
    EMAIL_INVALID_FORMAT(HttpStatus.BAD_REQUEST, "USER_012", "이메일 형식이 올바르지 않습니다"),
    EMAIL_UNDELIVERABLE(HttpStatus.BAD_REQUEST, "USER_013", "수신할 수 없는 이메일 주소입니다 (도메인을 확인해주세요)"),
    EMAIL_RESEND_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "USER_014", "잠시 후 다시 시도해주세요"),
    EMAIL_IP_RATE_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "USER_015", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요"),
    EMAIL_IP_DISTINCT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "USER_016", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요"),
    ADMIN_CANNOT_MODIFY_SELF(HttpStatus.BAD_REQUEST, "USER_017", "자기 자신의 권한·상태는 변경할 수 없습니다"),
    INVALID_ROLE(HttpStatus.BAD_REQUEST, "USER_018", "잘못된 권한 값입니다"),
    LOGIN_ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "USER_019", "Too many failed login attempts. Please try again in 10 minutes"),

    // ============= 메시지 관련 에러 (MSG) =============
    MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "MSG_001", "메시지를 찾을 수 없습니다"),
    UNAUTHORIZED_MESSAGE_ACCESS(HttpStatus.FORBIDDEN, "MSG_002", "메시지 접근 권한이 없습니다"),
    UNAUTHORIZED_MESSAGE_DELETE(HttpStatus.FORBIDDEN, "MSG_003", "메시지 삭제 권한이 없습니다"),
    UNAUTHORIZED_PIN_MESSAGE(HttpStatus.FORBIDDEN, "MSG_004", "메시지 고정 권한이 없습니다"),

    // ============= 채널 관련 에러 (CHN) =============
    CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, "CHN_001", "채널을 찾을 수 없습니다"),
    DUPLICATE_CHANNEL_NAME(HttpStatus.CONFLICT, "CHN_002", "이미 존재하는 채널명입니다"),
    UNAUTHORIZED_CHANNEL_ACCESS(HttpStatus.FORBIDDEN, "CHN_003", "채널 접근 권한이 없습니다"),
    UNAUTHORIZED_CHANNEL_UPDATE(HttpStatus.FORBIDDEN, "CHN_004", "채널 수정 권한이 없습니다"),
    UNAUTHORIZED_CHANNEL_DELETE(HttpStatus.FORBIDDEN, "CHN_005", "채널 삭제 권한이 없습니다"),
    ARCHIVED_CHANNEL(HttpStatus.FORBIDDEN, "CHN_006", "아카이브된 채널입니다"),
    CHANNEL_FULL(HttpStatus.BAD_REQUEST, "CHN_007", "채널 최대 인원을 초과했습니다"),
    INVALID_CHANNEL_PASSWORD(HttpStatus.BAD_REQUEST, "CHN_008", "채널 비밀번호가 잘못되었습니다"),
    CHANNEL_APPROVAL_REQUIRED(HttpStatus.BAD_REQUEST, "CHN_009", "채널 참여 승인이 필요합니다"),

    // ============= 멤버 관련 에러 (MBR) =============
    NOT_CHANNEL_MEMBER(HttpStatus.NOT_FOUND, "MBR_001", "채널 멤버가 아닙니다"),
    ALREADY_CHANNEL_MEMBER(HttpStatus.CONFLICT, "MBR_002", "이미 채널 멤버입니다"),
    UNAUTHORIZED_ADD_MEMBER(HttpStatus.FORBIDDEN, "MBR_003", "멤버 추가 권한이 없습니다"),
    UNAUTHORIZED_REMOVE_MEMBER(HttpStatus.FORBIDDEN, "MBR_004", "멤버 제거 권한이 없습니다"),
    UNAUTHORIZED_UPDATE_MEMBER(HttpStatus.FORBIDDEN, "MBR_005", "멤버 정보 수정 권한이 없습니다"),
    UNAUTHORIZED_UPDATE_ROLE(HttpStatus.FORBIDDEN, "MBR_006", "역할 변경 권한이 없습니다"),
    UNAUTHORIZED_MUTE_MEMBER(HttpStatus.FORBIDDEN, "MBR_007", "멤버 음소거 권한이 없습니다"),
    UNAUTHORIZED_INVITE(HttpStatus.FORBIDDEN, "MBR_008", "초대 권한이 없습니다"),
    OWNER_MUST_TRANSFER_ROLE(HttpStatus.BAD_REQUEST, "MBR_009","OWNER는 먼저 권한을 위임해야 합니다"),

    // ============= 번역 관련 에러 (TRANS) =============
    TRANSLATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "TRANS_001", "Translation failed"),
    TRANSLATION_NOT_FOUND(HttpStatus.NOT_FOUND, "TRANS_002", "Translation not found"),
    INVALID_LANGUAGE_PAIR(HttpStatus.BAD_REQUEST, "TRANS_003", "Invalid language pair"),
    TRANSLATION_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "TRANS_004", "Translation limit exceeded"),
    IMPORT_FAILED(HttpStatus.BAD_REQUEST, "TRANS_005", "Failed to import translations"),
    EXPORT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "TRANS_006", "Failed to export translations"),
    PRONUNCIATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "TRANS_007", "Failed to generate pronunciation"),
    LANGUAGE_DETECTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "TRANS_008", "Failed to detect language"),
    TRANSLATION_ALREADY_SAVED(HttpStatus.CONFLICT, "TRANS_009", "번역이 이미 저장되어 있습니다"),
    TRANSLATION_GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "TRANS_010", "번역 그룹을 찾을 수 없습니다"),
    TRANSLATION_HISTORY_LIMIT(HttpStatus.BAD_REQUEST, "TRANS_011", "번역 히스토리 제한 초과"),

    // ============= 여행 관련 에러 (TRV) =============
    TRAVEL_NOT_FOUND(HttpStatus.NOT_FOUND, "TRV_001", "여행 프로젝트를 찾을 수 없습니다"),
    UNAUTHORIZED_TRAVEL_ACCESS(HttpStatus.FORBIDDEN, "TRV_002", "여행 프로젝트 접근 권한이 없습니다"),
    UNAUTHORIZED_TRAVEL_UPDATE(HttpStatus.FORBIDDEN, "TRV_003", "여행 프로젝트 수정 권한이 없습니다"),
    UNAUTHORIZED_TRAVEL_DELETE(HttpStatus.FORBIDDEN, "TRV_004", "여행 프로젝트 삭제 권한이 없습니다"),
    ALREADY_TRAVEL_MEMBER(HttpStatus.CONFLICT, "TRV_005", "이미 여행 멤버입니다"),
    NOT_TRAVEL_MEMBER(HttpStatus.NOT_FOUND, "TRV_006", "여행 멤버가 아닙니다"),
    UNAUTHORIZED_MEMBER_MANAGE(HttpStatus.FORBIDDEN, "TRV_007", "멤버 관리 권한이 없습니다"),
    CANNOT_REMOVE_LAST_ADMIN(HttpStatus.BAD_REQUEST, "TRV_008", "마지막 관리자는 삭제할 수 없습니다"),
    TRAVEL_MEDIA_NOT_FOUND(HttpStatus.NOT_FOUND, "TRV_009", "미디어를 찾을 수 없습니다"),
    TRAVEL_SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "TRV_010", "일정을 찾을 수 없습니다"),
    INVALID_TRAVEL_DATE(HttpStatus.BAD_REQUEST, "TRV_011", "여행 날짜가 올바르지 않습니다"),
    INVALID_MEDIA_TYPE(HttpStatus.BAD_REQUEST, "TRV_012", "사진 또는 영상 파일만 업로드 가능합니다"),
    S3_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "TRV_013", "파일 업로드에 실패했습니다"),
    S3_DOWNLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "TRV_014", "파일 다운로드에 실패했습니다"),
    TRAVEL_CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, "TRV_015", "여행 채널을 찾을 수 없습니다"),
    CHANNEL_ALREADY_LINKED(HttpStatus.CONFLICT, "TRV_016", "이미 연결된 채널입니다"),
    TRAVEL_UPLOAD_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "TRV_017", "하루 업로드 가능한 파일 수(500개)를 초과했습니다"),

    // ============= 블로그 에러 (BLOG) =============
    BLOG_PUBLISH_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "BLOG_001", "하루 발행 가능한 글 수(8개)를 초과했습니다"),

    // ============= 문의함 에러 (INQ) =============
    INQUIRY_NOT_FOUND(HttpStatus.NOT_FOUND, "INQ_001", "문의를 찾을 수 없습니다"),

    // ============= 파일 에러 (FILE) =============
    INVALID_FILE_KEY(HttpStatus.BAD_REQUEST, "FILE_001", "허용되지 않은 파일 경로입니다"),

    // ============= 일반 에러 (GEN) =============
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "GEN_001", "Invalid input / 잘못된 입력값입니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "GEN_002", "Resource not found"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "GEN_003", "Unauthorized"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "GEN_004", "Forbidden"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "GEN_005", "Validation failed"),

    // ============= 서버 에러 (SERVER) =============
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SERVER_001", "Internal server error / 서버 내부 오류가 발생했습니다"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SERVER_002", "Service temporarily unavailable"),
    REQUEST_TIMEOUT(HttpStatus.REQUEST_TIMEOUT, "SERVER_003", "Request timeout"),
    EXTERNAL_API_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "SERVER_004", "External API error");

    private final HttpStatus status;
    private final String code;
    private final String msg;
}