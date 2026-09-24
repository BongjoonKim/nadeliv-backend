package server.nadeliv.inquiry.repo;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import server.nadeliv.inquiry.model.Inquiry;

import java.util.Optional;

@Repository
public interface InquiryRepo extends MongoRepository<Inquiry, String> {

    // 웹훅 멱등성 — 같은 SES messageId 는 한 번만 저장
    Optional<Inquiry> findByMessageId(String messageId);

    boolean existsByMessageId(String messageId);

    // primitive boolean 필드는 필드명 그대로 파생 쿼리 (isDeleted → IsDeletedFalse, BlogsRepo 와 동일)
    long countByIsDeletedFalse();

    long countByIsDeletedFalseAndIsReadFalse();
}
