package server.nadeliv.travel.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import server.nadeliv.travel.model.entities.TravelMedia;

import java.util.List;

@Repository
public interface TravelMediaRepo extends MongoRepository<TravelMedia, String> {

    Page<TravelMedia> findByTravelIdOrderByCreatedDesc(String travelId, Pageable pageable);

    // 정렬은 Pageable 의 Sort 로 지정 (created / takenAt)
    Page<TravelMedia> findByTravelId(String travelId, Pageable pageable);

    // mimeType 접두사 필터 ("image/" | "video/")
    Page<TravelMedia> findByTravelIdAndMimeTypeStartingWith(String travelId, String mimePrefix, Pageable pageable);

    Long countByTravelIdAndMimeTypeStartingWith(String travelId, String mimePrefix);

    List<TravelMedia> findByTravelId(String travelId);

    void deleteByTravelId(String travelId);

    Long countByTravelId(String travelId);
}
