package server.nadeliv.travel.repo;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import server.nadeliv.travel.model.entities.TravelMediaUpload;
import server.nadeliv.travel.model.enums.MediaUploadStatus;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TravelMediaUploadRepo extends MongoRepository<TravelMediaUpload, String> {

    List<TravelMediaUpload> findTop100ByStatusInAndExpiresAtBefore(List<MediaUploadStatus> statuses, LocalDateTime time);

    List<TravelMediaUpload> findByTravelId(String travelId);

    void deleteByTravelId(String travelId);
}
