package server.nadeliv.travel.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MediaUploadPartsRequest {
    /** 재발급할 part 번호 (1부터). 비우면 전체 */
    private List<Integer> partNumbers;
}
