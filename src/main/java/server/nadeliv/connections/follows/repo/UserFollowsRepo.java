package server.nadeliv.connections.follows.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import server.nadeliv.connections.follows.model.UserFollow;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserFollowsRepo extends MongoRepository<UserFollow, String> {

    Optional<UserFollow> findByFollowerIdAndFollowingId(String followerId, String followingId);

    void deleteByFollowerIdAndFollowingId(String followerId, String followingId);

    boolean existsByFollowerIdAndFollowingId(String followerId, String followingId);

    // followerId 가 follow 하고 있는 사용자 목록 (= 내가 팔로우한 사람들)
    List<UserFollow> findByFollowerId(String followerId);

    // 페이지네이션 버전
    Page<UserFollow> findByFollowerId(String followerId, Pageable pageable);

    // followingId 의 팔로워 목록 (= 나를 팔로우한 사람들)
    List<UserFollow> findByFollowingId(String followingId);

    Page<UserFollow> findByFollowingId(String followingId, Pageable pageable);

    long countByFollowerId(String followerId);

    long countByFollowingId(String followingId);

    // 회원 탈퇴 시 해당 사용자가 포함된 팔로우 관계 정리
    void deleteByFollowerId(String followerId);

    void deleteByFollowingId(String followingId);
}
