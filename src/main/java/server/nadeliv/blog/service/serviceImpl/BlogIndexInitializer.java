package server.nadeliv.blog.service.serviceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.TextIndexDefinition;
import org.springframework.data.mongodb.core.index.TextIndexDefinition.TextIndexDefinitionBuilder;
import org.springframework.stereotype.Component;
import server.nadeliv.blog.model.Documents;
import server.nadeliv.connections.follows.model.UserFollow;

/**
 * 블로그 목록 페이지에 필요한 인덱스를 애플리케이션 시작 시 보장.
 * Spring autoIndexCreation 이 꺼져 있는 환경에서 누락된 인덱스를 채우는 용도.
 * MongoDB ensureIndex 는 멱등적이므로 매번 실행해도 안전.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BlogIndexInitializer {

    private final MongoTemplate mongoTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        try {
            // 1) documents 컬렉션 텍스트 인덱스 (title weight 10, contents weight 1)
            TextIndexDefinition textIndex = new TextIndexDefinitionBuilder()
                    .onField("title", 10F)
                    .onField("contents", 1F)
                    .named("documents_text_idx")
                    .build();
            mongoTemplate.indexOps(Documents.class).ensureIndex(textIndex);

            // 2) 검색에 자주 쓰이는 필드 단일 인덱스
            mongoTemplate.indexOps(Documents.class)
                    .ensureIndex(new Index().on("updated", org.springframework.data.domain.Sort.Direction.DESC)
                            .named("documents_updated_idx"));
            // 목록 정렬 기준(생성일)
            mongoTemplate.indexOps(Documents.class)
                    .ensureIndex(new Index().on("created", org.springframework.data.domain.Sort.Direction.DESC)
                            .named("documents_created_idx"));
            mongoTemplate.indexOps(Documents.class)
                    .ensureIndex(new Index().on("createdUser", org.springframework.data.domain.Sort.Direction.ASC)
                            .named("documents_createdUser_idx"));

            // 3) UserFollow 복합 unique 인덱스 (Spring @CompoundIndex 가 적용되지 않는 환경 대비)
            mongoTemplate.indexOps(UserFollow.class)
                    .ensureIndex(new Index()
                            .on("followerId", org.springframework.data.domain.Sort.Direction.ASC)
                            .on("followingId", org.springframework.data.domain.Sort.Direction.ASC)
                            .unique()
                            .named("follower_following_unique_idx"));

            log.info("블로그/팔로우 인덱스 초기화 완료");
        } catch (Exception e) {
            // 인덱스 생성 실패는 앱 시작을 막지 않음. 운영 환경에서 권한 부족 등의 경우 로그만 남김.
            log.warn("인덱스 초기화 실패 (앱은 정상 동작): {}", e.getMessage());
        }
    }
}
