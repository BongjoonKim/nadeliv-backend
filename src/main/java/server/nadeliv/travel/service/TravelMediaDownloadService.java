package server.nadeliv.travel.service;

import jakarta.servlet.http.HttpServletResponse;
import server.nadeliv.travel.model.dto.MediaDownloadTicketResponse;
import server.nadeliv.travel.model.dto.MediaDownloadUrlResponse;

import java.util.List;

/**
 * 앨범 다운로드 (웹·iOS 공용).
 * - 단일: S3 presigned GET URL 발급 → 클라이언트가 S3 에서 직접 받음
 * - 일괄: 인증된 요청으로 티켓 발급 → 비인증 GET 으로 ZIP 스트리밍 (브라우저 메모리에 담지 않음)
 */
public interface TravelMediaDownloadService {

    MediaDownloadUrlResponse createDownloadUrl(String travelId, String mediaId, String userId);

    MediaDownloadTicketResponse createTicket(String travelId, List<String> mediaIds, String userId);

    /** 티켓의 미디어를 ZIP 으로 묶어 response 에 바로 쓴다 (헤더 포함) */
    void streamZip(String ticket, HttpServletResponse response);

    /** 티켓 없이 인증으로 바로 받는 구버전 경로 호환용 */
    void streamZip(String travelId, List<String> mediaIds, String userId, HttpServletResponse response);
}
