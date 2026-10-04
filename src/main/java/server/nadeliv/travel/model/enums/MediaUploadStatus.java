package server.nadeliv.travel.model.enums;

public enum MediaUploadStatus {
    PENDING,     // URL 발급됨, 클라이언트가 S3 에 올리는 중
    COMPLETING,  // complete 요청 처리 중 (중복 complete 방지용 잠금)
    COMPLETED,   // TravelMedia 등록 완료
    ABORTED      // 취소·만료
}
