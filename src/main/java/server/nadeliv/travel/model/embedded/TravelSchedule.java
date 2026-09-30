package server.nadeliv.travel.model.embedded;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 여행 일정 — 하루(dayNumber) 단위 코스 (Triple 식).
 * places 순서 = 그날 방문 순서. 프론트는 dayNumber 당 하나의 일정만 사용한다.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TravelSchedule {
    private String id;
    private Integer dayNumber;
    private LocalDate date;
    private String title;
    private String description;
    private List<SchedulePlace> places;
    private Integer sortOrder;

    /** 하루 코스의 한 항목. 좌표가 없으면 직접 입력한 일정(메모)이다. */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class SchedulePlace {
        // 항목 식별자 (프론트에서 생성 — 정렬·이동 시 동일 항목 추적용)
        private String id;
        // 검색 제공자(카카오) 장소 ID
        private String placeId;
        private String name;
        private String nameEn;
        private String category;
        private String categoryEn;
        private String address;
        private Double lat;
        private Double lng;
        // 방문 예정 시각 "HH:mm" (선택)
        private String time;
        private String memo;
    }
}
