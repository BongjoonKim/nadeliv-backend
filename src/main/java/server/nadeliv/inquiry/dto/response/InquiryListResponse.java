package server.nadeliv.inquiry.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InquiryListResponse {
    private List<InquiryResponse> inquiries;
    private Long totalCount;      // 현재 필터(status) 기준 총 수
    private Integer totalPages;
    private Integer currentPage;
    private Boolean hasNext;

    // 필터와 무관한 전체 통계 (삭제 제외)
    private Long totalAll;
    private Long unreadCount;
}
