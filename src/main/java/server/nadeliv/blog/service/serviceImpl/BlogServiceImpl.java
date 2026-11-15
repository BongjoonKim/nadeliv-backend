package server.nadeliv.blog.service.serviceImpl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import server.nadeliv.blog.constants.BlogConstants;
import server.nadeliv.blog.dto.DocumentsDTO;
import server.nadeliv.blog.dto.DocumentsInfo;
import server.nadeliv.blog.dto.PaginationDTO;
import server.nadeliv.blog.dto.PopularPostDTO;
import server.nadeliv.blog.model.DocumentView;
import server.nadeliv.blog.model.Documents;
import server.nadeliv.blog.repo.BlogsRepo;
import server.nadeliv.blog.service.BlogService;
import server.nadeliv.common.service.RateLimitService;
import server.nadeliv.error.CustomException;
import server.nadeliv.error.ErrorCode;
import server.nadeliv.connections.bookmarks.repo.BookmarksRepo;
import server.nadeliv.connections.follows.service.UserFollowService;
import server.nadeliv.i18n.model.entities.PostTranslation;
import server.nadeliv.i18n.model.enums.TranslationStatus;
import server.nadeliv.i18n.repo.PostTranslationRepo;
import server.nadeliv.i18n.service.I18nTranslationService;
import server.nadeliv.users.model.Users;
import server.nadeliv.users.repo.UsersRepo;
import org.bson.Document;  // 이 import 추가 필요!


import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BlogServiceImpl implements BlogService {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private BlogsRepo blogsRepo;

    @Autowired
    private UsersRepo usersRepo;

    @Autowired
    private BookmarksRepo bookmarksRepo;

    @Autowired
    private I18nTranslationService i18nTranslationService;

    @Autowired
    private PostTranslationRepo postTranslationRepo;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private UserFollowService userFollowService;

    @Autowired
    private RateLimitService rateLimitService;

    private final ObjectMapper popularObjectMapper = new ObjectMapper();

    private static final String POPULAR_CACHE_PREFIX = "blog:popular:";
    private static final Duration POPULAR_CACHE_TTL = Duration.ofMinutes(15);

    // 하루 발행(draft→published 전환) 가능 글 수 제한 (남용 방지)
    private static final String SCOPE_BLOG_PUBLISH = "blog:publish";
    private static final int MAX_DAILY_PUBLISH = 8;

    private void testAggregationSteps(
            AggregationOperation matchDraft,
            AggregationOperation lookupBookmarks,
            AggregationOperation matchUserBookmarks,
            String userId) {

        // Step 1: matchDraft 테스트
        Aggregation test1 = Aggregation.newAggregation(matchDraft);
        List<Documents> step1Results = mongoTemplate.aggregate(
                test1, "documents", Documents.class
        ).getMappedResults();
        System.out.println("Step 1 - After matchDraft: " + step1Results.size() + " documents");

        // Step 2: lookup 테스트 - org.bson.Document 사용!
        Aggregation test2 = Aggregation.newAggregation(matchDraft, lookupBookmarks);
        List<Document> step2Results = mongoTemplate.aggregate(
                test2, "documents", Document.class  // org.bson.Document 사용
        ).getMappedResults();

        System.out.println("Step 2 - After lookup: " + step2Results.size() + " documents");

        // bookmarks 배열 확인
        for (Document doc : step2Results) {  // Document는 org.bson.Document
            List<Document> bookmarks = (List<Document>) doc.get("bookmarks");
            if (bookmarks != null && !bookmarks.isEmpty()) {
                System.out.println("  문서 ID: " + doc.get("_id"));
                System.out.println("  bookmarks 개수: " + bookmarks.size());

                // 각 bookmark 내용 출력
                for (Document bookmark : bookmarks) {
                    System.out.println("    - userId: " + bookmark.get("userId") +
                            ", isBookmarked: " + bookmark.get("isBookmarked") +
                            ", documentId: " + bookmark.get("documentId"));
                }
            }
        }

        // 현재 사용자의 북마크가 있는지 확인
        long userBookmarkCount = step2Results.stream()
                .filter(doc -> {
                    List<Document> bookmarks = (List<Document>) doc.get("bookmarks");
                    if (bookmarks == null) return false;
                    return bookmarks.stream().anyMatch(b ->
                            userId.equals(b.get("userId")) &&
                                    Boolean.TRUE.equals(b.get("isBookmarked"))
                    );
                })
                .count();

        System.out.println("Step 2.5 - 현재 사용자(" + userId + ")의 북마크가 있는 문서 수: " + userBookmarkCount);

        // Step 3: matchUserBookmarks 테스트
        Aggregation test3 = Aggregation.newAggregation(
                matchDraft,
                lookupBookmarks,
                matchUserBookmarks
        );
        List<Documents> step3Results = mongoTemplate.aggregate(
                test3, "documents", Documents.class  // 여기는 Documents 클래스 사용 가능
        ).getMappedResults();

        System.out.println("Step 3 - After user filter: " + step3Results.size() + " documents");

        if (step3Results.isEmpty() && userBookmarkCount > 0) {
            System.out.println("⚠️ 경고: lookup 후에는 사용자 북마크가 있었지만, elemMatch 필터 후 결과가 없습니다!");
            System.out.println("=== 대체 방법 테스트 ===");

            // 대체 방법 1: unwind 사용
            testUnwindMethod(matchDraft, lookupBookmarks, userId);
        }
    }

    // 대체 방법 테스트 메서드도 추가
    private void testUnwindMethod(
            AggregationOperation matchDraft,
            AggregationOperation lookupBookmarks,
            String userId) {

        System.out.println("=== Unwind 방법 테스트 ===");

        AggregationOperation unwind = Aggregation.unwind("bookmarks", false);
        AggregationOperation matchDirect = Aggregation.match(
                Criteria.where("bookmarks.userId").is(userId)
                        .and("bookmarks.isBookmarked").is(true)
        );

        Aggregation unwindAgg = Aggregation.newAggregation(
                matchDraft,
                lookupBookmarks,
                unwind,
                matchDirect
        );

        List<Documents> unwindResults = mongoTemplate.aggregate(
                unwindAgg, "documents", Documents.class
        ).getMappedResults();

        System.out.println("Unwind 방법 결과: " + unwindResults.size() + " documents");

        if (!unwindResults.isEmpty()) {
            System.out.println("✅ Unwind 방법이 작동합니다! elemMatch 대신 이 방법을 사용하세요.");
        }
    }

    private void testAggregationStepsWithAddFields(
            AggregationOperation matchDraft,
            AggregationOperation addFields,
            AggregationOperation lookupBookmarks,
            AggregationOperation matchUserBookmarks,
            String userId) {

        // Step 1: matchDraft 테스트
        Aggregation test1 = Aggregation.newAggregation(matchDraft);
        List<Documents> step1Results = mongoTemplate.aggregate(
                test1, "documents", Documents.class
        ).getMappedResults();
        System.out.println("Step 1 - After matchDraft: " + step1Results.size() + " documents");

        // Step 2: addFields 테스트
        Aggregation test2 = Aggregation.newAggregation(matchDraft, addFields, lookupBookmarks);
        List<Document> step2Results = mongoTemplate.aggregate(
                test2, "documents", Document.class
        ).getMappedResults();

        System.out.println("Step 2 - After addFields and lookup: " + step2Results.size() + " documents");

        // bookmarks 배열 확인
        for (Document doc : step2Results) {
            List<Document> bookmarks = (List<Document>) doc.get("bookmarks");
            if (bookmarks != null && !bookmarks.isEmpty()) {
                System.out.println("  문서 ID: " + doc.get("_id"));
                System.out.println("  문서 _idStr: " + doc.get("_idStr"));  // 변환된 string ID 확인
                System.out.println("  bookmarks 개수: " + bookmarks.size());

                for (Document bookmark : bookmarks) {
                    System.out.println("    - userId: " + bookmark.get("userId") +
                            ", isBookmarked: " + bookmark.get("isBookmarked") +
                            ", documentId: " + bookmark.get("documentId"));
                }
            }
        }

        // Step 3: 최종 필터링
        Aggregation test3 = Aggregation.newAggregation(
                matchDraft,
                addFields,
                lookupBookmarks,
                matchUserBookmarks
        );
        List<Documents> step3Results = mongoTemplate.aggregate(
                test3, "documents", Documents.class
        ).getMappedResults();

        System.out.println("Step 3 - After user filter: " + step3Results.size() + " documents");
    }

    @Override
    public DocumentsDTO createDocument(DocumentsDTO documentsDTO) {

        Documents documents = new Documents();
        BeanUtils.copyProperties(documentsDTO, documents);
        LocalDateTime now = LocalDateTime.now();

        documents.setCreated(now);
        documents.setUpdated(now);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() != null) {
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String username = userDetails.getUsername();
            documents.setCreatedUser(username);
            documents.setUpdatedUser(username);

            // 남용 방지: 일반 플로우는 draft=true 로 생성하지만, 프론트를 우회해
            // 바로 발행(draft=false)으로 생성하는 경우도 하루 8개 제한에 포함시킨다.
            boolean isDirectPublish = !documents.isDraft();
            if (isDirectPublish && rateLimitService.isDailyLimitReached(SCOPE_BLOG_PUBLISH, username, MAX_DAILY_PUBLISH)) {
                throw new CustomException(ErrorCode.BLOG_PUBLISH_LIMIT_EXCEEDED);
            }

            if (documents.getFolderId() == null) {
                Users users = usersRepo.findByUserId(username);
                documents.setFolderId(users.getId());
            }
            Documents afterDocument = blogsRepo.save(documents);

            if (isDirectPublish) {
                rateLimitService.incrementDaily(SCOPE_BLOG_PUBLISH, username);
            }

            DocumentsDTO newDocDTO = new DocumentsDTO();
            BeanUtils.copyProperties(afterDocument, newDocDTO);

            return newDocDTO;
        }
        return null;
    }

    @Override
    public DocumentsDTO saveDocument(DocumentsDTO documentsDTO) {
        try {
            // 로그인 필수
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !(authentication.getPrincipal() instanceof UserDetails)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
            }
            String username = ((UserDetails) authentication.getPrincipal()).getUsername();

            // 기존 문서 로드 후 작성자 본인 여부 검증 (남의 글 수정 차단)
            Documents existing = (documentsDTO.getId() != null)
                    ? blogsRepo.findById(documentsDTO.getId()).orElse(null)
                    : null;
            if (existing != null && existing.getCreatedUser() != null
                    && !username.equals(existing.getCreatedUser())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not allowed to edit this document");
            }

            Documents documents = new Documents();
            BeanUtils.copyProperties(documentsDTO, documents);
            LocalDateTime now = LocalDateTime.now();
            documents.setUpdated(now);
            documents.setUpdatedUser(username);

            // 작성자/생성시각/휴지통 상태는 DTO로 위조하지 못하도록 기존 값 보존
            if (existing != null) {
                documents.setCreatedUser(existing.getCreatedUser());
                documents.setCreated(existing.getCreated());
                documents.setDeleted(existing.isDeleted());
                documents.setDeletedAt(existing.getDeletedAt());
            }

            // 남용 방지: 새로 발행되는 경우(draft→published 전환)만 하루 8개 제한.
            // 이미 발행된 글을 다시 저장/수정하는 것은 카운트하지 않는다.
            boolean isNewPublish = !documents.isDraft() && (existing == null || existing.isDraft());
            if (isNewPublish && rateLimitService.isDailyLimitReached(SCOPE_BLOG_PUBLISH, username, MAX_DAILY_PUBLISH)) {
                throw new CustomException(ErrorCode.BLOG_PUBLISH_LIMIT_EXCEEDED);
            }

            if (documents.getFolderId() == null) {
                Users users = usersRepo.findByUserId(username);
                documents.setFolderId(users.getId());
            }
            Documents afterDocument = blogsRepo.save(documents);

            // 발행 성공 후에만 카운트 증가 (실패한 저장은 쿼터 소모 안 함)
            if (isNewPublish) {
                rateLimitService.incrementDaily(SCOPE_BLOG_PUBLISH, username);
            }

            // i18n: 발행된 글(draft가 아닌)이면 자동 번역 큐잉
            if (!afterDocument.isDraft()) {
                try {
                    i18nTranslationService.queueTranslations(afterDocument);
                } catch (Exception e) {
                    log.warn("번역 큐잉 실패 (글 저장은 성공): {}", e.getMessage());
                }
            }

            DocumentsDTO newDocDTO = new DocumentsDTO();
            BeanUtils.copyProperties(afterDocument, newDocDTO);
            return newDocDTO;
        } catch (Exception e) {
            throw e;
        }
    }


    @Override
    public DocumentsInfo getDocuments(PaginationDTO pageDTO) throws Exception {
        try {
            Page<Documents> documents = null;
            // 목록 정렬은 항상 생성일 기준 — 글을 수정해도 순서가 바뀌지 않는다
            Sort.Order createdSort = "ASC".equals(pageDTO.getDateSort()) ? Sort.Order.asc("created") : Sort.Order.desc("created");
            Sort sort = Sort.by(createdSort);

            Pageable pageable = PageRequest.of(pageDTO.getPage(), pageDTO.getSize(), sort);

            if ("all".equals(pageDTO.getFolderId()) || pageDTO.getPage() == -1) {
                documents = blogsRepo.findAllByDraftIsFalseOrDraftIsNullAndIsDeletedFalse(pageable);
            } else {
                if (ObjectUtils.isEmpty(pageDTO.getPageType())) {
                    documents = blogsRepo.findAllByDraftIsFalseOrDraftIsNullAndIsDeletedFalse(pageable);
                } else {
                    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                    if (authentication != null && authentication.getPrincipal() != null) {
                        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
                        String username = userDetails.getUsername();
                        Users users = usersRepo.findByUserId(username);
                        if (!ObjectUtils.isEmpty(users)) {
                            if (BlogConstants.BlogPageType.MY_BLOG.getValue().equals(pageDTO.getPageType())) {
                                documents = this.findByCreatedUserOrUpdatedUserAndDraft(users.getUserId(), users.getUserId(), pageable, false);
                            } else if (BlogConstants.BlogPageType.BOOKMARK.getValue().equals(pageDTO.getPageType())) {
                                // 디버깅 모드
                                boolean debugMode = false; // 배포 시 false로 변경

                                if (debugMode) {
                                    System.out.println("=== BOOKMARK 조회 시작 ===");
                                    System.out.println("User ID: " + users.getUserId());
                                }

                                // 1. Draft 필터 + 휴지통 제외
                                AggregationOperation matchDraft = Aggregation.match(
                                        Criteria.where("draft").ne(true)
                                                .and("isDeleted").ne(true)
                                );

                                // 2. Lookup - 이제 타입 변환 없이 직접 사용 가능
                                AggregationOperation lookupBookmarks = Aggregation.lookup(
                                        "con_bookmarks_users_documents",
                                        "_id",  // ObjectId 타입 그대로 사용
                                        "documentId",  // Bookmark의 documentId도 ObjectId 타입
                                        "bookmarks"
                                );

                                // 3. Match bookmarks - 현재 사용자가 북마크한 문서만 필터
                                AggregationOperation matchUserBookmarks = Aggregation.match(
                                        Criteria.where("bookmarks").elemMatch(
                                                Criteria.where("userId").is(users.getUserId())
                                                        .and("isBookmarked").is(true)
                                        )
                                );

                                // 4. Sort, Skip, Limit
                                AggregationOperation sortOperation = Aggregation.sort(sort);
                                AggregationOperation skipOperation = Aggregation.skip(
                                        (long) pageable.getPageNumber() * pageable.getPageSize()
                                );
                                AggregationOperation limitOperation = Aggregation.limit(pageable.getPageSize());

                                // 5. 전체 aggregation (addFields 제거)
                                Aggregation aggregation = Aggregation.newAggregation(
                                        matchDraft,
                                        lookupBookmarks,
                                        matchUserBookmarks,
                                        sortOperation,
                                        skipOperation,
                                        limitOperation
                                );

                                // 6. Count aggregation (addFields 제거)
                                Aggregation countAggregation = Aggregation.newAggregation(
                                        matchDraft,
                                        lookupBookmarks,
                                        matchUserBookmarks,
                                        Aggregation.count().as("total")
                                );

                                // 7. 실행
                                AggregationResults<Documents> results = mongoTemplate.aggregate(
                                        aggregation, "documents", Documents.class
                                );

                                if (debugMode) {
                                    System.out.println("최종 결과 개수: " + results.getMappedResults().size());
                                }

                                // 8. 카운트 수행
                                CountResult countResult = mongoTemplate.aggregate(
                                        countAggregation, "documents", CountResult.class
                                ).getUniqueMappedResult();

                                long totalCount = countResult != null ? countResult.getTotal() : 0;

                                documents = PageableExecutionUtils.getPage(
                                        results.getMappedResults(),
                                        pageable,
                                        () -> totalCount
                                );
                            } else if (BlogConstants.BlogPageType.DRAFT.getValue().equals(pageDTO.getPageType())) {
                                documents = this.findByCreatedUserOrUpdatedUserAndDraft(users.getUserId(), users.getUserId(), pageable, true);
                            } else if (BlogConstants.BlogPageType.HIDDEN.getValue().equals(pageDTO.getPageType())) {
                                documents = this.findByCreatedUserAndHidden(users.getUserId(), pageable);
                            } else if (BlogConstants.BlogPageType.TRASH.getValue().equals(pageDTO.getPageType())) {
                                documents = this.findTrashedByCreatedUser(users.getUserId(), pageable);
                            }
                        } else {
                            throw new Exception("there is no user : " + username);
                        }
                    } else {
                        throw new Exception("there is no login information");
                    }
                }
            }
            List<DocumentsDTO> documentsDTO = documents.getContent().stream().map(document -> {
                DocumentsDTO documentDTO = new DocumentsDTO();
                BeanUtils.copyProperties(document, documentDTO);
                return documentDTO;
            }).collect(Collectors.toList());

            // locale이 지정되고 원본 언어(ko)가 아닌 경우, 번역된 제목/내용으로 덮어쓰기
            String locale = pageDTO.getLocale();
            if (StringUtils.hasText(locale) && !"ko".equals(locale) && !documentsDTO.isEmpty()) {
                applyTranslations(documentsDTO, locale);
            }

            DocumentsInfo documentsInfo = new DocumentsInfo();
            documentsInfo.setDocuments(documentsDTO);
            documentsInfo.setTotalPagesCnt(documents.getTotalPages());
            documentsInfo.setTotalDocsCnt(documents.getTotalElements());
            return documentsInfo;
        } catch (Exception e) {
            throw e;
        }
    }

    @Override
    public DocumentsInfo searchDocuments(String value, PaginationDTO pageDTO) throws Exception {
        try {
            Pageable pageable = PageRequest.of(pageDTO.getPage(), pageDTO.getSize());

            // 1) MongoDB text index 기반 검색 (relevance score 순). 인덱스가 없는 환경이거나
            //    결과가 비면 기존 regex 폴백으로 전환.
            List<Documents> textResults;
            long total;
            try {
                org.springframework.data.mongodb.core.query.TextCriteria textCriteria =
                        org.springframework.data.mongodb.core.query.TextCriteria
                                .forDefaultLanguage()
                                .matchingAny(value);

                org.springframework.data.mongodb.core.query.TextQuery textQuery =
                        org.springframework.data.mongodb.core.query.TextQuery.queryText(textCriteria)
                                .sortByScore();
                textQuery.addCriteria(Criteria.where("disclose").is(true)
                        .and("isDeleted").ne(true)
                        .and("draft").ne(true));
                textQuery.with(pageable);

                textResults = mongoTemplate.find(textQuery, Documents.class);

                org.springframework.data.mongodb.core.query.Query countQuery = new Query();
                countQuery.addCriteria(org.springframework.data.mongodb.core.query.TextCriteria
                        .forDefaultLanguage().matchingAny(value));
                countQuery.addCriteria(Criteria.where("disclose").is(true)
                        .and("isDeleted").ne(true)
                        .and("draft").ne(true));
                total = mongoTemplate.count(countQuery, Documents.class);
            } catch (Exception textError) {
                log.warn("Text search 실패, regex 폴백: {}", textError.getMessage());
                textResults = null;
                total = 0;
            }

            Page<Documents> documents;
            if (textResults != null && !textResults.isEmpty()) {
                documents = new PageImpl<>(textResults, pageable, total);
            } else {
                // 폴백: 기존 regex 검색 (짧은 검색어 or 텍스트 인덱스 미적용 환경)
                Sort.Order createdSort = "ASC".equals(pageDTO.getDateSort())
                        ? Sort.Order.asc("created") : Sort.Order.desc("created");
                Sort sort = Sort.by(createdSort);
                Pageable regexPageable = PageRequest.of(pageDTO.getPage(), pageDTO.getSize(), sort);
                documents = blogsRepo.findByTitleOrContentsWithDisclose(value, value, regexPageable);
            }

            List<DocumentsDTO> documentsDTO = new ArrayList<>();
            DocumentsInfo documentsInfo = new DocumentsInfo();

            if (!ObjectUtils.isEmpty(documents.getContent())) {
                documents.getContent().stream().forEach(content -> {
                    DocumentsDTO documentDTO = new DocumentsDTO();
                    BeanUtils.copyProperties(content, documentDTO);
                    documentsDTO.add(documentDTO);
                });
                documentsInfo.setDocuments(documentsDTO);
                documentsInfo.setTotalDocsCnt(documents.getTotalElements());
                documentsInfo.setTotalPagesCnt(documents.getTotalPages());
            } else {
                documentsInfo.setDocuments(new ArrayList<>());
                documentsInfo.setTotalDocsCnt(0);
                documentsInfo.setTotalPagesCnt(0);
            }
            return documentsInfo;
        } catch (Exception e) {
            throw e;
        }
    }

    @Override
    public DocumentsDTO createAfterSaveDocument(DocumentsDTO newData) {
        // 로그인 필수
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetails)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();

        Documents documents = blogsRepo.findById(newData.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "document not found"));

        // 작성자 본인만 수정 가능
        if (documents.getCreatedUser() != null && !username.equals(documents.getCreatedUser())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not allowed to edit this document");
        }

        documents.setContents(newData.getContents());
        documents.setThumbnailImgUrl(newData.getThumbnailImgUrl());
        documents.setUpdated(LocalDateTime.now());
        documents.setUpdatedUser(username);

        Documents newDocument = blogsRepo.save(documents);
        DocumentsDTO newDocumentDTO = new DocumentsDTO();
        BeanUtils.copyProperties(newDocument, newDocumentDTO);

        return newDocumentDTO;
    }

    @Override
    public DocumentsDTO getDocument(String id) throws Exception {
        try {
            Documents documents = blogsRepo.findById(id).get();
            // 휴지통 글은 본인만 조회 가능 (휴지통 화면용)
            if (documents.isDeleted()) {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                String username = (authentication != null && authentication.getPrincipal() instanceof UserDetails)
                        ? ((UserDetails) authentication.getPrincipal()).getUsername()
                        : null;
                if (username == null || !username.equals(documents.getCreatedUser())) {
                    throw new Exception("document not available");
                }
            }
            DocumentsDTO documentsDTO = new DocumentsDTO();
            BeanUtils.copyProperties(documents, documentsDTO);
            return documentsDTO;
        } catch (Exception e) {
            throw e;
        }
    }

    @Override
    public void deleteDocument(String id) throws Exception {
        try {
            // Soft delete: 휴지통으로 이동. 90일 후 BlogCleanupScheduler가 영구 삭제 처리.
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String username = (authentication != null && authentication.getPrincipal() instanceof UserDetails)
                    ? ((UserDetails) authentication.getPrincipal()).getUsername()
                    : null;

            // 반드시 로그인해야 삭제 가능 (익명 삭제 차단)
            if (username == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
            }

            Documents document = blogsRepo.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "document not found: " + id));

            // 작성자 본인만 삭제 가능
            if (document.getCreatedUser() != null
                    && !username.equals(document.getCreatedUser())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not allowed to delete this document");
            }

            LocalDateTime now = LocalDateTime.now();
            Query query = new Query(Criteria.where("_id").is(id));
            org.springframework.data.mongodb.core.query.Update update =
                    new org.springframework.data.mongodb.core.query.Update()
                            .set("isDeleted", true)
                            .set("deletedAt", now)
                            .set("updated", now);
            if (username != null) {
                update.set("updatedUser", username);
            }
            mongoTemplate.updateFirst(query, update, Documents.class);
            log.info("Soft deleted document: id={}, deletedAt={}", id, now);
        } catch (Exception e) {
            throw e;
        }
    }

    @Override
    public void restoreDocument(String id) throws Exception {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetails)) {
            throw new Exception("there is no login information");
        }
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();

        Documents document = blogsRepo.findById(id)
                .orElseThrow(() -> new Exception("document not found: " + id));

        if (document.getCreatedUser() != null && !username.equals(document.getCreatedUser())) {
            throw new Exception("not allowed to restore this document");
        }
        if (!document.isDeleted()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Query query = new Query(Criteria.where("_id").is(id));
        org.springframework.data.mongodb.core.query.Update update =
                new org.springframework.data.mongodb.core.query.Update()
                        .set("isDeleted", false)
                        .unset("deletedAt")
                        .set("updated", now)
                        .set("updatedUser", username);
        mongoTemplate.updateFirst(query, update, Documents.class);
        log.info("Restored document: id={}, by={}", id, username);
    }

    // 내부적으로 카운트를 담을 DTO
    private static class CountResult {
        private long total;

        public long getTotal() {
            return total;
        }

        public void setTotal(long total) {
            this.total = total;
        }
    }

    private Page<Documents> findByCreatedUserOrUpdatedUserAndDraft(String createdUser, String updatedUser, Pageable pageable, boolean isDraft) {
        // 조건 생성: 본인 글 + draft 일치 + 휴지통 제외
        Criteria criteria = new Criteria().andOperator(
                new Criteria().orOperator(
                        Criteria.where("createdUser").is(createdUser),
                        Criteria.where("updatedUser").is(updatedUser)
                ),
                Criteria.where("draft").is(isDraft),
                Criteria.where("isDeleted").ne(true)
        );

        // 쿼리 생성
        Query query = new Query(criteria);

        // 페이지네이션 적용
        long total = mongoTemplate.count(query, Documents.class);  // 전체 데이터 개수 계산
        query.with(pageable);  // Pageable로 페이징 정보 적용

        // 데이터 조회
        List<Documents> entities = mongoTemplate.find(query, Documents.class);

        // Page 객체로 반환 (페이지네이션 정보와 결과 리스트)
        return new PageImpl<>(entities, pageable, total);
    };

    private Page<Documents> findByCreatedUserAndHidden(String createdUser, Pageable pageable) {
        Criteria criteria = Criteria.where("createdUser").is(createdUser)
                .and("disclose").is(false)
                .and("draft").is(false)
                .and("isDeleted").ne(true);

        Query query = new Query(criteria);
        long total = mongoTemplate.count(query, Documents.class);
        query.with(pageable);

        List<Documents> entities = mongoTemplate.find(query, Documents.class);
        return new PageImpl<>(entities, pageable, total);
    }

    // 휴지통: soft-deleted 문서 목록 조회 (본인 글만)
    private Page<Documents> findTrashedByCreatedUser(String createdUser, Pageable pageable) {
        Criteria criteria = Criteria.where("createdUser").is(createdUser)
                .and("isDeleted").is(true);

        Query query = new Query(criteria);
        long total = mongoTemplate.count(query, Documents.class);
        query.with(pageable);

        List<Documents> entities = mongoTemplate.find(query, Documents.class);
        return new PageImpl<>(entities, pageable, total);
    }

    /**
     * 블로그 목록의 DocumentsDTO에 번역된 제목/내용을 덮어쓰기.
     * COMPLETED 또는 MANUALLY_EDITED 상태의 번역만 적용.
     */
    private void applyTranslations(List<DocumentsDTO> documentsDTO, String locale) {
        try {
            List<String> postIds = documentsDTO.stream()
                    .map(DocumentsDTO::getId)
                    .collect(Collectors.toList());

            List<PostTranslation> translations = postTranslationRepo.findByPostIdInAndLocaleAndStatusIn(
                    postIds, locale,
                    List.of(TranslationStatus.COMPLETED, TranslationStatus.MANUALLY_EDITED)
            );

            Map<String, PostTranslation> translationMap = translations.stream()
                    .collect(Collectors.toMap(PostTranslation::getPostId, t -> t, (a, b) -> a));

            for (DocumentsDTO dto : documentsDTO) {
                PostTranslation translation = translationMap.get(dto.getId());
                if (translation != null) {
                    if (StringUtils.hasText(translation.getTitle())) {
                        dto.setTitle(translation.getTitle());
                    }
                    if (StringUtils.hasText(translation.getSummary())) {
                        dto.setContents(translation.getSummary());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("블로그 목록 번역 적용 실패 (원본 유지): {}", e.getMessage());
        }
    }

    @Override
    public DocumentsDTO setAsFeatured(String id, Documents.FeaturedInfo featuredInfo,
                                      LocalDateTime startDate, LocalDateTime endDate,
                                      String approvedBy) {
        try {
            Documents document = blogsRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("문서를 찾을 수 없습니다"));

            // Draft 상태인 글은 Featured로 설정 불가
            if (document.isDraft()) {
                throw new RuntimeException("임시저장 상태의 글은 Featured로 설정할 수 없습니다");
            }

            // 동일 기간에 이미 활성화된 Featured 확인
            List<Documents> conflictingDocs = blogsRepo
                    .findFeaturedDocumentsByDateRange(startDate, endDate);

            if (!conflictingDocs.isEmpty() && !conflictingDocs.get(0).getId().equals(id)) {
                throw new RuntimeException("해당 기간에 이미 Featured 글이 존재합니다");
            }

            // Featured 정보 설정
            if (featuredInfo == null) {
                // 기본 Featured 정보 생성
                featuredInfo = Documents.FeaturedInfo.builder()
                        .featuredTitle(document.getTitle())
                        .featuredSubtitle("Your Smart Travel Manager for Korea")
                        .featuredImageUrl(document.getThumbnailImgUrl())
                        .displayPriority(1)
                        .build();
            }

            // Featured 스케줄 설정
            Documents.FeaturedSchedule schedule = Documents.FeaturedSchedule.builder()
                    .startDate(startDate)
                    .endDate(endDate)
                    .isActive(true)
                    .approvedBy(approvedBy)
                    .approvedAt(LocalDateTime.now())
                    .build();

            document.setFeaturedReady(true);
            document.setFeaturedInfo(featuredInfo);
            document.setFeaturedSchedule(schedule);
            document.setUpdated(LocalDateTime.now());
            document.setUpdatedUser(approvedBy);

            Documents savedDocument = blogsRepo.save(document);

            DocumentsDTO resultDTO = new DocumentsDTO();
            BeanUtils.copyProperties(savedDocument, resultDTO);

            log.info("문서를 Featured로 설정: {} (기간: {} ~ {})", id, startDate, endDate);
            return resultDTO;

        } catch (Exception e) {
            log.error("Featured 설정 실패: {}", id, e);
            throw new RuntimeException("Featured 설정 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public DocumentsDTO removeFromFeatured(String id) {
        try {
            Documents document = blogsRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("문서를 찾을 수 없습니다"));

            // Featured 관련 정보 모두 제거
            document.setFeaturedReady(false);
            document.setFeaturedInfo(null);
            document.setFeaturedSchedule(null);
            document.setUpdated(LocalDateTime.now());

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) {
                document.setUpdatedUser(auth.getName());
            }

            Documents savedDocument = blogsRepo.save(document);

            DocumentsDTO resultDTO = new DocumentsDTO();
            BeanUtils.copyProperties(savedDocument, resultDTO);

            log.info("Featured 설정 해제: {}", id);
            return resultDTO;

        } catch (Exception e) {
            log.error("Featured 해제 실패: {}", id, e);
            throw new RuntimeException("Featured 해제 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public DocumentsDTO updateFeaturedInfo(String id, Documents.FeaturedInfo featuredInfo) {
        try {
            Documents document = blogsRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("문서를 찾을 수 없습니다"));

            if (!document.isFeaturedReady()) {
                throw new RuntimeException("Featured로 설정되지 않은 문서입니다");
            }

            document.setFeaturedInfo(featuredInfo);
            document.setUpdated(LocalDateTime.now());

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) {
                document.setUpdatedUser(auth.getName());
            }

            Documents savedDocument = blogsRepo.save(document);

            DocumentsDTO resultDTO = new DocumentsDTO();
            BeanUtils.copyProperties(savedDocument, resultDTO);

            log.info("Featured 정보 수정: {}", id);
            return resultDTO;

        } catch (Exception e) {
            log.error("Featured 정보 수정 실패: {}", id, e);
            throw new RuntimeException("Featured 정보 수정 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public List<DocumentsDTO> getActiveFeaturedDocuments(int limit) {
        LocalDateTime now = LocalDateTime.now();

        // 우선순위 순으로 정렬하여 활성화된 Featured 문서 조회
        Sort sort = Sort.by(Sort.Direction.ASC, "featuredInfo.displayPriority");
        List<Documents> activeDocs = blogsRepo
                .findActiveFeaturedDocumentsOrderByPriority(now, sort);

        // limit 적용
        return activeDocs.stream()
                .limit(limit)
                .map(doc -> {
                    DocumentsDTO dto = new DocumentsDTO();
                    BeanUtils.copyProperties(doc, dto);
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Override
    public DocumentsInfo getFeaturableDocuments(int page, int size, String search) {
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "created"));

        Page<Documents> documents;

        if (StringUtils.hasText(search)) {
            // 검색어가 있으면 제목/내용에서 검색
            documents = blogsRepo.findByDraftFalseAndFeaturedReadyFalseAndIsDeletedFalseAndTitleContainingOrContentsContaining(
                    search, search, pageable);
        } else {
            // Featured가 아니고 draft가 아닌 모든 글
            documents = blogsRepo.findByDraftFalseAndFeaturedReadyFalseAndIsDeletedFalse(pageable);
        }

        List<DocumentsDTO> dtoList = documents.getContent().stream()
                .map(doc -> {
                    DocumentsDTO dto = new DocumentsDTO();
                    BeanUtils.copyProperties(doc, dto);
                    return dto;
                })
                .collect(Collectors.toList());

        DocumentsInfo info = new DocumentsInfo();
        info.setDocuments(dtoList);
        info.setTotalDocsCnt(documents.getTotalElements());
        info.setTotalPagesCnt(documents.getTotalPages());

        return info;
    }

    @Override
    public DocumentsInfo getFeaturedHistory(int page, int size) {
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "featuredSchedule.approvedAt"));

        // Featured로 설정된 적이 있는 모든 문서 (활성/비활성 포함, 휴지통 제외)
        Page<Documents> featuredDocs = blogsRepo.findByFeaturedReadyTrueAndIsDeletedFalse(pageable);

        List<DocumentsDTO> dtoList = featuredDocs.getContent().stream()
                .map(doc -> {
                    DocumentsDTO dto = new DocumentsDTO();
                    BeanUtils.copyProperties(doc, dto);
                    return dto;
                })
                .collect(Collectors.toList());

        DocumentsInfo info = new DocumentsInfo();
        info.setDocuments(dtoList);
        info.setTotalDocsCnt(featuredDocs.getTotalElements());
        info.setTotalPagesCnt(featuredDocs.getTotalPages());

        return info;
    }

    @Override
    public List<PopularPostDTO> getPopularPosts(String period, int limit) {
        // 입력 정규화
        String normalizedPeriod = StringUtils.hasText(period) ? period.toLowerCase() : "month";
        int normalizedLimit = Math.max(1, Math.min(limit, 20));
        String cacheKey = POPULAR_CACHE_PREFIX + normalizedPeriod + ":" + normalizedLimit;

        // Redis 캐시 확인
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return popularObjectMapper.readValue(cached, new TypeReference<List<PopularPostDTO>>() {});
            }
        } catch (Exception e) {
            log.warn("Popular posts 캐시 역직렬화 실패 (DB로 폴백): {}", e.getMessage());
        }

        // 기간 시작점 계산
        LocalDateTime since = resolvePopularSince(normalizedPeriod);

        // documents_views 컬렉션에서 documentId별 조회수 집계
        List<AggregationOperation> stages = new ArrayList<>();
        if (since != null) {
            stages.add(Aggregation.match(Criteria.where("viewedAt").gte(since)));
        }
        stages.add(Aggregation.group("documentId").count().as("viewCount"));
        stages.add(Aggregation.sort(Sort.Direction.DESC, "viewCount"));
        // 일부 결과는 비공개/삭제 글일 수 있으므로 limit의 4배까지 후보로 가져온 뒤 필터링
        stages.add(Aggregation.limit((long) normalizedLimit * 4));

        Aggregation aggregation = Aggregation.newAggregation(stages);
        AggregationResults<PopularAggResult> aggResults = mongoTemplate.aggregate(
                aggregation, DocumentView.class, PopularAggResult.class
        );

        List<PopularAggResult> rawCounts = aggResults.getMappedResults();
        if (rawCounts.isEmpty()) {
            cachePopularResult(cacheKey, Collections.emptyList());
            return Collections.emptyList();
        }

        // 후보 문서들을 한 번에 조회 후 공개/비삭제만 필터 (N+1 방지)
        List<String> candidateIds = rawCounts.stream()
                .map(PopularAggResult::getId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());

        Map<String, Documents> documentsById = blogsRepo.findAllById(candidateIds).stream()
                .filter(doc -> !doc.isDraft() && !doc.isDeleted() && doc.isDisclose())
                .collect(Collectors.toMap(Documents::getId, doc -> doc, (a, b) -> a));

        // 집계 순서를 유지하며 결과 빌드
        List<PopularPostDTO> result = new ArrayList<>();
        int rank = 1;
        for (PopularAggResult agg : rawCounts) {
            if (result.size() >= normalizedLimit) break;
            Documents doc = documentsById.get(agg.getId());
            if (doc == null) continue;
            result.add(PopularPostDTO.builder()
                    .rank(rank++)
                    .id(doc.getId())
                    .title(doc.getTitle())
                    .thumbnailImgUrl(doc.getThumbnailImgUrl())
                    .viewCount(agg.getViewCount())
                    .build());
        }

        cachePopularResult(cacheKey, result);
        return result;
    }

    private LocalDateTime resolvePopularSince(String period) {
        LocalDateTime now = LocalDateTime.now();
        return switch (period) {
            case "day" -> now.minus(1, ChronoUnit.DAYS);
            case "week" -> now.minus(7, ChronoUnit.DAYS);
            case "month" -> now.minus(30, ChronoUnit.DAYS);
            case "all" -> null;
            default -> now.minus(30, ChronoUnit.DAYS);
        };
    }

    private void cachePopularResult(String cacheKey, List<PopularPostDTO> result) {
        try {
            String serialized = popularObjectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(cacheKey, serialized, POPULAR_CACHE_TTL);
        } catch (Exception e) {
            log.warn("Popular posts 캐시 직렬화 실패: {}", e.getMessage());
        }
    }

    // 집계 결과 매핑용. _id 는 documentId 가 들어옴.
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class PopularAggResult {
        @org.springframework.data.annotation.Id
        private String id;
        private long viewCount;
    }

    @Override
    public DocumentsInfo getFollowingFeed(String viewerUserId, PaginationDTO pageDTO) {
        DocumentsInfo empty = new DocumentsInfo();
        empty.setDocuments(new ArrayList<>());
        empty.setTotalDocsCnt(0);
        empty.setTotalPagesCnt(0);

        if (viewerUserId == null || viewerUserId.isBlank()) {
            return empty;
        }

        List<String> followingIds = userFollowService.getFollowingUserIds(viewerUserId);
        if (followingIds.isEmpty()) {
            return empty;
        }

        Sort.Order createdSort = "ASC".equals(pageDTO.getDateSort())
                ? Sort.Order.asc("created") : Sort.Order.desc("created");
        Sort sort = Sort.by(createdSort);
        Pageable pageable = PageRequest.of(pageDTO.getPage(), pageDTO.getSize(), sort);

        Criteria criteria = Criteria.where("createdUser").in(followingIds)
                .and("draft").ne(true)
                .and("isDeleted").ne(true)
                .and("disclose").is(true);

        Query query = new Query(criteria);
        long total = mongoTemplate.count(query, Documents.class);
        query.with(pageable);
        List<Documents> docs = mongoTemplate.find(query, Documents.class);

        List<DocumentsDTO> dtoList = docs.stream().map(doc -> {
            DocumentsDTO dto = new DocumentsDTO();
            BeanUtils.copyProperties(doc, dto);
            return dto;
        }).collect(Collectors.toList());

        // 번역 적용 (기존 로직 재사용)
        String locale = pageDTO.getLocale();
        if (StringUtils.hasText(locale) && !"ko".equals(locale) && !dtoList.isEmpty()) {
            applyTranslations(dtoList, locale);
        }

        DocumentsInfo result = new DocumentsInfo();
        result.setDocuments(dtoList);
        result.setTotalDocsCnt(total);
        int totalPages = pageable.getPageSize() == 0
                ? 0 : (int) Math.ceil((double) total / pageable.getPageSize());
        result.setTotalPagesCnt(totalPages);
        return result;
    }

    @Override
    public long countFollowingFeedSince(String viewerUserId, LocalDateTime since) {
        if (viewerUserId == null || viewerUserId.isBlank()) return 0L;
        List<String> followingIds = userFollowService.getFollowingUserIds(viewerUserId);
        if (followingIds.isEmpty()) return 0L;

        Criteria criteria = Criteria.where("createdUser").in(followingIds)
                .and("draft").ne(true)
                .and("isDeleted").ne(true)
                .and("disclose").is(true);
        if (since != null) {
            criteria = criteria.and("updated").gte(since);
        }
        return mongoTemplate.count(new Query(criteria), Documents.class);
    }
}


