package server.nadeliv.travel.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MediaUploadInitRequest {
    @NotBlank
    private String fileName;
    @NotBlank
    private String contentType;
    @NotNull
    @Positive
    private Long fileSize;

    private Integer width;
    private Integer height;
    private Integer duration;
    private String description;
    private LocalDateTime takenAt;
}
