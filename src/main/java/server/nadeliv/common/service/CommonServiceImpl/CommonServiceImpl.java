package server.nadeliv.common.service.CommonServiceImpl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import server.nadeliv.common.dto.OgMetadataDTO;
import server.nadeliv.common.service.CommonService;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class CommonServiceImpl implements CommonService {
    @Value("${spring.weather.url}")
    private String weatherUrl;

    @Value("${spring.weather.key}")
    private String weatherKey;

    private static final int TIMEOUT_MS = 5000;
    // 실제 브라우저 UA. naver.me, instagram, twitter 등은 봇 UA에 og 메타가 빈 미니멀 페이지를 줌.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36";

    // 단축 링크 전용 봇 UA. maps.app.goo.gl 은 브라우저 UA 에는 JS 중간 페이지(200)를 주고
    // 리다이렉트를 하지 않지만, 일반 UA 에는 302 로 최종 지도 URL 을 돌려준다.
    private static final String BOT_USER_AGENT =
            "Mozilla/5.0 (compatible; NadelivBot/1.0; +https://www.nadeliv.com)";

    // 먼저 리다이렉트를 풀어 최종 URL 을 알아내야 하는 단축 링크 호스트
    private static final Set<String> SHORT_LINK_HOSTS =
            Set.of("naver.me", "maps.app.goo.gl", "goo.gl", "g.co", "kko.to");

    // 네이버 지도/플레이스 URL 에서 장소 id 추출
    //   https://map.naver.com/p/entry/place/11491428, /v5/entry/place/{id}, m.place.naver.com/place/{id},
    //   m.place.naver.com/restaurant/{id}/home 등
    private static final Pattern NAVER_PLACE_ID = Pattern.compile(
            "/(?:p/entry/place|v5/entry/place|place|restaurant|cafe|accommodation|hospital|attraction|hairshop|beauty)/(\\d+)");
    private static final Pattern NAVER_JSON_FIELD_CATEGORY = Pattern.compile("\"category\":\"([^\"\\\\]{1,60})\"");
    private static final Pattern NAVER_JSON_FIELD_ROAD_ADDRESS = Pattern.compile("\"roadAddress\":\"([^\"\\\\]{1,120})\"");
    private static final Pattern NAVER_JSON_FIELD_ADDRESS = Pattern.compile("\"address\":\"([^\"\\\\]{1,120})\"");
    private static final String NAVER_MAP_FAVICON = "https://ssl.pstatic.net/static/maps/assets/icons/favicon.ico";

    // 구글 지도 URL: https://www.google.co.kr/maps/place/{장소명}/@lat,lng,15z/data=...
    private static final Pattern GOOGLE_MAPS_HOST = Pattern.compile("^(www\\.|maps\\.)?google\\.[a-z.]+$");
    private static final Pattern GOOGLE_MAPS_PLACE_PATH = Pattern.compile("^/maps/(place|search)/([^/]+)(/.*)?$");
    private static final Pattern GOOGLE_AT_COORDS = Pattern.compile("@(-?\\d{1,3}\\.\\d+),(-?\\d{1,3}\\.\\d+)");
    // 장소 페이지 초기 상태 JSON 안의 장소 좌표 ([null,null,lat,lng])
    private static final Pattern GOOGLE_EMBEDDED_COORDS = Pattern.compile("\\[null,null,(-?\\d{1,3}\\.\\d{3,}),(-?\\d{1,3}\\.\\d{3,})\\]");
    private static final String GOOGLE_FAVICON = "https://www.google.com/favicon.ico";

    @Override
    public String getWeatherData() throws Exception {
        RestTemplate restTemplate = new RestTemplate();

        String url = weatherUrl + "?appid=" + weatherKey + "&q=Seoul,kr";
        try {
            ResponseEntity<String> resData = restTemplate.getForEntity(url, String.class);
            return resData.getBody();
        } catch (Exception e) {
            throw e;
        }
    }

    @Override
    public OgMetadataDTO getOgMetadata(String url) throws Exception {
        // URL 유효성 검증 (SSRF 방지의 1차 방어선)
        URI uri = new URI(url);
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("http/https URL만 허용됩니다.");
        }

        // 1) 단축 링크(naver.me, maps.app.goo.gl 등)는 봇 UA 로 리다이렉트만 먼저 풀어 최종 URL 확보
        String resolvedUrl = url;
        if (uri.getHost() != null && SHORT_LINK_HOSTS.contains(uri.getHost().toLowerCase())) {
            resolvedUrl = resolveRedirects(url);
        }
        URI resolvedUri;
        try {
            resolvedUri = new URI(resolvedUrl);
        } catch (Exception e) {
            resolvedUri = uri;
        }

        // 2) 지도 서비스는 OG 가 비어 있거나 범용이라 전용 처리로 장소명·사진·지도 썸네일을 만든다
        try {
            OgMetadataDTO naver = tryNaverMapPreview(url, resolvedUri);
            if (naver != null) return naver;
            OgMetadataDTO google = tryGoogleMapsPreview(url, resolvedUri);
            if (google != null) return google;
        } catch (Exception e) {
            // 전용 처리가 실패하면 범용 OG 로 폴백 (최소한 favicon/siteName 은 채워진다)
            log.warn("지도 링크 전용 미리보기 실패, 범용 OG 로 폴백 url={} msg={}", url, e.getMessage());
        }

        // 3) 범용 OG 파싱
        return fetchGenericOg(url, resolvedUrl, resolvedUri);
    }

    /** 리다이렉트를 끝까지 따라간 최종 URL. 실패하면 원본 URL 을 그대로 돌려준다. */
    private String resolveRedirects(String url) {
        try {
            Connection.Response res = Jsoup.connect(url)
                    .userAgent(BOT_USER_AGENT)
                    .header("Accept-Language", "ko,en-US;q=0.9,en;q=0.8")
                    .timeout(TIMEOUT_MS)
                    .followRedirects(true)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .execute();
            return res.url().toString();
        } catch (Exception e) {
            log.debug("리다이렉트 해석 실패 url={} msg={}", url, e.getMessage());
            return url;
        }
    }

    private Document fetchDocument(String url, String userAgent) throws Exception {
        return Jsoup.connect(url)
                .userAgent(userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ko,en-US;q=0.9,en;q=0.8")
                .timeout(TIMEOUT_MS)
                .followRedirects(true)
                .ignoreHttpErrors(false)
                .get();
    }

    private OgMetadataDTO fetchGenericOg(String originalUrl, String fetchUrl, URI fallbackBase) throws Exception {
        Document doc = fetchDocument(fetchUrl, USER_AGENT);

        // redirect 이후 최종 URL을 base로 사용해야 상대 경로(og:image 등)가 올바르게 풀림.
        URI baseUri;
        try {
            baseUri = new URI(doc.location());
        } catch (Exception e) {
            baseUri = fallbackBase;
        }

        String title = firstNonEmpty(
                metaContent(doc, "meta[property=og:title]"),
                metaContent(doc, "meta[name=twitter:title]"),
                doc.title()
        );

        String description = firstNonEmpty(
                metaContent(doc, "meta[property=og:description]"),
                metaContent(doc, "meta[name=twitter:description]"),
                metaContent(doc, "meta[name=description]")
        );

        String image = firstNonEmpty(
                metaContent(doc, "meta[property=og:image]"),
                metaContent(doc, "meta[name=twitter:image]")
        );

        String siteName = firstNonEmpty(
                metaContent(doc, "meta[property=og:site_name]"),
                baseUri.getHost()
        );

        String favicon = firstNonEmpty(
                linkHref(doc, "link[rel~=(?i)^(shortcut icon|icon|apple-touch-icon)$]"),
                baseUri.getScheme() + "://" + baseUri.getHost() + "/favicon.ico"
        );

        // 상대 URL 보정 — redirect 후의 최종 URL 기준
        image = absolutize(image, baseUri);
        favicon = absolutize(favicon, baseUri);

        return OgMetadataDTO.builder()
                .url(originalUrl)
                .title(nullIfEmpty(title))
                .description(nullIfEmpty(description))
                .image(nullIfEmpty(image))
                .favicon(nullIfEmpty(favicon))
                .siteName(nullIfEmpty(siteName))
                .build();
    }

    /* ----------------------------------------------------------------------
     * 네이버 지도 / 플레이스
     *   map.naver.com 은 JS 앱이라 OG 에 장소 정보가 없다. 같은 장소 id 의
     *   m.place.naver.com/place/{id} 는 서버 렌더링이라 og:title(장소명)·og:image(대표 사진)와
     *   초기 상태 JSON(category / address) 을 준다.
     * -------------------------------------------------------------------- */
    private OgMetadataDTO tryNaverMapPreview(String originalUrl, URI resolvedUri) throws Exception {
        String host = resolvedUri.getHost() == null ? "" : resolvedUri.getHost().toLowerCase();
        if (!host.endsWith("naver.com") || !(host.contains("map.") || host.contains("place."))) {
            return null;
        }
        String path = resolvedUri.getPath() == null ? "" : resolvedUri.getPath();
        Matcher m = NAVER_PLACE_ID.matcher(path);
        if (!m.find()) return null;
        String placeId = m.group(1);

        Document doc = fetchDocument("https://m.place.naver.com/place/" + placeId, USER_AGENT);
        String html = doc.outerHtml();

        // "삼악산 : 네이버" → "삼악산"
        String title = firstNonEmpty(metaContent(doc, "meta[property=og:title]"), doc.title());
        if (title != null) {
            title = title.replaceFirst("\\s*:\\s*네이버\\s*$", "").trim();
        }

        String category = firstMatch(NAVER_JSON_FIELD_CATEGORY, html);
        String address = firstNonEmpty(
                firstMatch(NAVER_JSON_FIELD_ROAD_ADDRESS, html),
                firstMatch(NAVER_JSON_FIELD_ADDRESS, html)
        );
        String description;
        if (address != null && category != null) {
            description = category + " · " + address;
        } else if (address != null) {
            description = address;
        } else {
            description = metaContent(doc, "meta[property=og:description]");
        }

        String image = metaContent(doc, "meta[property=og:image]");
        String favicon = firstNonEmpty(
                linkHref(doc, "link[rel~=(?i)^(shortcut icon|icon|apple-touch-icon)$]"),
                NAVER_MAP_FAVICON
        );
        URI base = new URI("https://m.place.naver.com/");

        return OgMetadataDTO.builder()
                .url(originalUrl)
                .title(nullIfEmpty(title))
                .description(nullIfEmpty(description))
                .image(nullIfEmpty(absolutize(image, base)))
                .favicon(nullIfEmpty(absolutize(favicon, base)))
                .siteName("네이버 지도")
                .build();
    }

    /* ----------------------------------------------------------------------
     * 구글 지도
     *   /maps/place/{장소명}/... 의 OG 는 제목이 "Google Maps" 로 범용이고 og:image(정적 지도)는
     *   URL 의 @lat,lng 뷰포트를 중심으로 만들어진다. 장소명은 URL 경로에서 가져오고,
     *   좌표가 URL 에 없으면 페이지 초기 상태에서 찾아 @lat,lng 를 붙여 다시 받아
     *   장소 위치가 맞는 지도 썸네일을 얻는다.
     * -------------------------------------------------------------------- */
    private OgMetadataDTO tryGoogleMapsPreview(String originalUrl, URI resolvedUri) throws Exception {
        String host = resolvedUri.getHost() == null ? "" : resolvedUri.getHost().toLowerCase();
        if (!GOOGLE_MAPS_HOST.matcher(host).matches()) return null;
        String rawPath = resolvedUri.getRawPath() == null ? "" : resolvedUri.getRawPath();
        Matcher pm = GOOGLE_MAPS_PLACE_PATH.matcher(rawPath);
        if (!pm.matches()) return null;

        String kind = pm.group(1);            // place | search
        String nameSegment = pm.group(2);     // URL 인코딩된 장소명 (+ 는 공백)
        String rest = pm.group(3) == null ? "" : pm.group(3);
        String name = URLDecoder.decode(nameSegment, StandardCharsets.UTF_8).trim();

        String fetchUrl = resolvedUri.toString();
        Document doc = fetchDocument(fetchUrl, BOT_USER_AGENT);

        // 좌표: URL 의 @lat,lng 우선, 없으면 페이지 초기 상태에서
        String lat = null, lng = null;
        Matcher am = GOOGLE_AT_COORDS.matcher(rawPath);
        if (am.find()) {
            lat = am.group(1);
            lng = am.group(2);
        } else {
            Matcher em = GOOGLE_EMBEDDED_COORDS.matcher(doc.outerHtml());
            if (em.find()) {
                lat = em.group(1);
                lng = em.group(2);
                // @lat,lng 를 끼워 넣은 URL 로 다시 받으면 og:image 가 그 위치 중심이 된다
                String query = resolvedUri.getRawQuery() == null ? "" : "?" + resolvedUri.getRawQuery();
                String centered = resolvedUri.getScheme() + "://" + resolvedUri.getHost()
                        + "/maps/" + kind + "/" + nameSegment + "/@" + lat + "," + lng + ",15z" + rest + query;
                try {
                    doc = fetchDocument(centered, BOT_USER_AGENT);
                } catch (Exception e) {
                    log.debug("구글 지도 좌표 중심 재요청 실패, 기본 og:image 사용 url={} msg={}", centered, e.getMessage());
                }
            }
        }

        String image = firstNonEmpty(
                metaContent(doc, "meta[property=og:image]"),
                metaContent(doc, "meta[name=twitter:image]")
        );
        String favicon = firstNonEmpty(
                linkHref(doc, "link[rel~=(?i)^(shortcut icon|icon|apple-touch-icon)$]"),
                GOOGLE_FAVICON
        );
        URI base = new URI(resolvedUri.getScheme() + "://" + resolvedUri.getHost() + "/");

        return OgMetadataDTO.builder()
                .url(originalUrl)
                .title(nullIfEmpty(name))
                // 주소 등은 JS 로 로드돼 서버 HTML 에 없다. 범용 영문 설명은 노이즈라 비운다.
                .description(null)
                .image(nullIfEmpty(absolutize(image, base)))
                .favicon(nullIfEmpty(absolutize(favicon, base)))
                .siteName("Google 지도")
                .build();
    }

    private String firstMatch(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private String metaContent(Document doc, String selector) {
        Element el = doc.selectFirst(selector);
        return el == null ? null : el.attr("content");
    }

    private String linkHref(Document doc, String selector) {
        Element el = doc.selectFirst(selector);
        return el == null ? null : el.attr("href");
    }

    private String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private String nullIfEmpty(String v) {
        return (v == null || v.isBlank()) ? null : v;
    }

    private String absolutize(String maybeRelative, URI base) {
        if (maybeRelative == null || maybeRelative.isBlank()) return null;
        try {
            URI resolved = base.resolve(maybeRelative);
            return resolved.toString();
        } catch (Exception e) {
            return maybeRelative;
        }
    }
}
