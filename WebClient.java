import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebClient {

    private static final String HOST = "localhost";
    private static final int PORT = 8080;
    private static final String APP_ORIGIN = "http://" + HOST + ":" + PORT;

    private static final String HOME_URL = "https://www.coccoc.com";
    private static final String SEARCH_URL =
            "https://www.google.com/search?q=";

    // ----- YouTube Data API v3 -----
    // Dan API key cua ban vao day (hoac dat bien moi truong YOUTUBE_API_KEY).
    // KHONG chia se file nay cho nguoi khac khi da dien key.
    private static final String YOUTUBE_API_KEY = "AIzaSyBptBPhbvajaSf_LkvHK-zUp0QLfstr7e8";
    private static final String YOUTUBE_API_BASE =
            System.getenv().getOrDefault(
                    "YOUTUBE_API_BASE",
                    "https://www.googleapis.com/youtube/v3");

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(HOST, PORT),
                0
        );

        server.createContext("/", new HomeHandler());
        server.createContext("/proxy", new ProxyHandler());
        server.createContext("/youtube", new YouTubeHandler());
        server.createContext("/ytapi", new YouTubeApiHandler());
        server.createContext("/search", new SearchHandler());
        server.createContext("/health", new HealthHandler());

        server.setExecutor(
                Executors.newCachedThreadPool()
        );

        server.start();

        System.out.println("---------------------------------------");
        System.out.println("Mini Coc Coc dang chay");
        System.out.println("Mo trinh duyet tai: http://localhost:8080");
        System.out.println("Nhan Ctrl + C de dung server");
        System.out.println("---------------------------------------");
    }

    // ============================================================
    // HOME HANDLER
    // ============================================================

    private static class HomeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method =
                    exchange.getRequestMethod();

            String path =
                    exchange.getRequestURI().getPath();

            if (!"GET".equalsIgnoreCase(method)) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }

            if ("/favicon.ico".equals(path)) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            if (!"/".equals(path)) {
                sendText(exchange, 404, "Not Found");
                return;
            }

            byte[] data =
                    buildHomePage().getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "text/html; charset=UTF-8"
            );

            exchange.sendResponseHeaders(200, data.length);

            try (OutputStream output =
                         exchange.getResponseBody()) {
                output.write(data);
            }
        }
    }

    // ============================================================
    // HEALTH HANDLER
    // ============================================================

    private static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] data =
                    "{\"status\":\"ok\"}"
                            .getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "application/json; charset=UTF-8"
            );

            exchange.sendResponseHeaders(200, data.length);

            try (OutputStream output =
                         exchange.getResponseBody()) {
                output.write(data);
            }
        }
    }

    // ============================================================
    // PROXY HANDLER
    // ============================================================

    private static class ProxyHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(
                    exchange.getRequestMethod())) {

                sendText(exchange, 405, "Method Not Allowed");
                return;
            }

            Map<String, String> query =
                    parseQuery(
                            exchange.getRequestURI().getRawQuery()
                    );

            String inputUrl = query.get("url");

            if (inputUrl == null || inputUrl.isBlank()) {
                sendText(exchange, 400,
                        "Thieu tham so url");
                return;
            }

            String normalizedUrl =
                    normalizeInput(inputUrl);

            URI targetUri;

            try {
                targetUri =
                    URI.create(normalizedUrl);
            } catch (Exception e) {
                sendText(exchange, 400,
                        "URL khong hop le: " + e.getMessage());
                return;
            }
            String searchQuery = SearchHandler.extractSearchQuery(targetUri);
            if (searchQuery != null) {
                exchange.getResponseHeaders().set(
                        "Location",
                        "/search?q=" + URLEncoder.encode(
                                searchQuery, StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
                String requestHost =
                    exchange.getRequestHeaders().getFirst("Host");

                String appOrigin =
                    (requestHost == null || requestHost.isBlank())
                        ? APP_ORIGIN
                        : "http://" + requestHost;

            String scheme =
                    targetUri.getScheme();

                String host =
                    targetUri.getHost() == null
                        ? ""
                        : targetUri.getHost().toLowerCase(Locale.ROOT);

                String path =
                    targetUri.getPath() == null
                        ? ""
                        : targetUri.getPath();

                if (isYoutubeVideoUrl(host, path)) {
                sendRedirect(exchange,
                    appOrigin + "/youtube?url="
                        + URLEncoder.encode(
                        targetUri.toString(),
                        StandardCharsets.UTF_8));
                return;
                }

            if (scheme == null
                    || (!"http".equalsIgnoreCase(scheme)
                    && !"https".equalsIgnoreCase(scheme))) {

                sendText(exchange, 400,
                        "Chi ho tro giao thuc HTTP va HTTPS");
                return;
            }

            try {
                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(targetUri)
                                .timeout(Duration.ofSeconds(30))
                                .header(
                                        "User-Agent",
                                        "Mozilla/5.0 " +
                                                "MiniCocCoc/1.0"
                                )
                                .header(
                                        "Accept",
                                        "text/html," +
                                                "application/xhtml+xml," +
                                                "application/xml;q=0.9," +
                                                "image/avif,image/webp," +
                                                "*/*;q=0.8"
                                )
                                .header(
                                        "Accept-Language",
                                        "vi-VN,vi;q=0.9,en;q=0.8"
                                )
                                .GET()
                                .build();

                HttpResponse<byte[]> response =
                        HTTP_CLIENT.send(
                                request,
                                HttpResponse.BodyHandlers
                                        .ofByteArray()
                        );

                int statusCode =
                        response.statusCode();

                byte[] body =
                        response.body();

                String contentType =
                        response.headers()
                                .firstValue("content-type")
                                .orElse(
                                        "application/octet-stream"
                                );

                String lowerContentType =
                        contentType.toLowerCase(
                                Locale.ROOT
                        );

                if (lowerContentType.contains("text/html")
                        || lowerContentType.contains(
                        "application/xhtml+xml")) {

                    Charset charset =
                            extractCharset(contentType);

                    String html =
                            new String(body, charset);

                    String transformed =
                            transformHtml(
                                    html,
                                targetUri,
                                appOrigin
                            );

                    body =
                            transformed.getBytes(
                                    StandardCharsets.UTF_8
                            );

                    contentType =
                            "text/html; charset=UTF-8";
                }

                Headers headers =
                        exchange.getResponseHeaders();

                headers.set(
                        "Content-Type",
                        contentType
                );

                headers.set(
                        "Cache-Control",
                        "no-store"
                );

                headers.set(
                        "X-Content-Type-Options",
                        "nosniff"
                );

                exchange.sendResponseHeaders(
                        statusCode,
                        body.length
                );

                try (OutputStream output =
                             exchange.getResponseBody()) {
                    output.write(body);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                sendText(exchange, 502,
                        "Ket noi bi gian doan");

            } catch (Exception e) {
                sendText(exchange, 502,
                        "Khong the tai trang: "
                                + e.getMessage());
            }
        }
    }

    private static class YouTubeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }

            Map<String, String> query =
                    parseQuery(exchange.getRequestURI().getRawQuery());

            String rawUrl = query.get("url");
            String providedId = query.get("id");
            String providedKeyword = query.get("q");

            String videoId =
                    providedId != null && !providedId.isBlank()
                            ? providedId.trim()
                            : extractYoutubeVideoId(rawUrl);

            String html;
            if (videoId != null && !videoId.isBlank()) {
                html = buildYouTubeVideoPage(videoId);
            } else {
                String keyword = extractYoutubeKeyword(rawUrl, providedKeyword);
                html = buildYouTubeSearchPage(keyword, rawUrl);
            }

            byte[] data = html.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "text/html; charset=UTF-8"
            );

            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(data);
            }
        }
    }

    // ============================================================
    // YOUTUBE API HANDLER (tim kiem video, key nam o server)
    // ============================================================

    private static class YouTubeApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }

            Map<String, String> query =
                    parseQuery(exchange.getRequestURI().getRawQuery());
            String q = query.getOrDefault("q", "").trim();

            if (q.isEmpty()) {
                sendJson(exchange, 400,
                        errorJson("Thieu tu khoa tim kiem"));
                return;
            }

            String key = getYoutubeApiKey();
            if (key.isBlank()) {
                sendJson(exchange, 503, errorJson(
                        "Chua co YouTube API key. Dien vao YOUTUBE_API_KEY "
                                + "trong WebClient.java roi chay lai server."));
                return;
            }

            try {
                String url = YOUTUBE_API_BASE
                        + "/search?part=snippet&type=video"
                        + "&videoEmbeddable=true&maxResults=12"
                        + "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                        + "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<byte[]> response = HTTP_CLIENT.send(
                        request, HttpResponse.BodyHandlers.ofByteArray());

                sendBytes(exchange, response.statusCode(),
                        "application/json; charset=UTF-8", response.body());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendJson(exchange, 502, errorJson("Ket noi bi gian doan"));
            } catch (Exception e) {
                sendJson(exchange, 502, errorJson(
                        "Khong goi duoc YouTube API: " + e.getMessage()));
            }
        }
    }

    private static String getYoutubeApiKey() {
        String fromEnv = System.getenv("YOUTUBE_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return YOUTUBE_API_KEY == null ? "" : YOUTUBE_API_KEY.trim();
    }

    // ============================================================
    // URL PROCESSING
    // ============================================================

    private static String normalizeInput(String input) {
        String text =
                input == null
                        ? ""
                        : input.trim();

        if (text.isBlank()) {
            return HOME_URL;
        }

        if (text.matches(
                "^[a-zA-Z][a-zA-Z0-9+.-]*://.*$")) {
            return text;
        }

        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("yt ") || lower.startsWith("youtube ")) {
            String keyword = text.substring(text.indexOf(' ') + 1).trim();
            if (!keyword.isBlank()) {
                return "https://www.youtube.com/results?search_query="
                        + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            }
        }

        if (text.matches(
                "^(localhost|127\\.0\\.0\\.1|" +
                        "\\d{1,3}(\\.\\d{1,3}){3})" +
                        "(:\\d+)?(/.*)?$")) {

            return "http://" + text;
        }

        if (text.contains(" ")) {
            return SEARCH_URL +
                    URLEncoder.encode(
                            text,
                            StandardCharsets.UTF_8
                    );
        }

        if (text.contains(".")) {
            return "https://" + text;
        }

        return SEARCH_URL +
                URLEncoder.encode(
                        text,
                        StandardCharsets.UTF_8
                );
    }

    private static Map<String, String> parseQuery(
            String rawQuery) {

        Map<String, String> result =
                new HashMap<>();

        if (rawQuery == null
                || rawQuery.isBlank()) {
            return result;
        }

        String[] parts =
                rawQuery.split("&");

        for (String part : parts) {
            int index =
                    part.indexOf('=');

            if (index < 0) {
                result.put(
                        urlDecode(part),
                        ""
                );
            } else {
                String key =
                        urlDecode(
                                part.substring(0, index)
                        );

                String value =
                        urlDecode(
                                part.substring(index + 1)
                        );

                result.put(key, value);
            }
        }

        return result;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(
                value,
                StandardCharsets.UTF_8
        );
    }

    private static Charset extractCharset(
            String contentType) {

        Pattern pattern =
                Pattern.compile(
                        "(?i)charset\\s*=\\s*([\\w-]+)"
                );

        Matcher matcher =
                pattern.matcher(contentType);

        if (matcher.find()) {
            try {
                return Charset.forName(
                        matcher.group(1).trim()
                );
            } catch (Exception ignored) {
                return StandardCharsets.UTF_8;
            }
        }

        return StandardCharsets.UTF_8;
    }

    // ============================================================
    // HTML PROCESSING
    // ============================================================

    private static String transformHtml(
            String originalHtml,
            URI baseUri,
            String appOrigin) {

        String rewritten =
                rewriteResourceLinks(
                        originalHtml,
                baseUri,
                appOrigin
                );

        String baseUrl =
                escapeHtml(baseUri.toString());

        String jsUrl =
                escapeJs(baseUri.toString());

        String injected = """
                    <base href="%s">
                <meta name="referrer" content="no-referrer">
                <meta name="viewport"
                      content="width=device-width, initial-scale=1">

                <style>
                    html, body {
                        max-width: 100%%;
                        overflow-x: auto;
                    }

                    img, video, iframe {
                        max-width: 100%%;
                    }

                    table {
                        max-width: 100%%;
                        overflow-x: auto;
                    }
                </style>

                <script>
                (() => {
                    const currentUrl = '%s';
                    const appOrigin = '%s';
                    
                    // Form GET: giu action goc, tu them ?url= khi gui
                    function goViaProxy(form) {
                        const method = (form.getAttribute('method') || 'get').toLowerCase();
                        if (method !== 'get') {
                            return false;
                        }
                        try {
                            const action = new URL(form.getAttribute('action') || currentUrl, currentUrl);
                            const data = new FormData(form);
                            for (const [key, value] of data.entries()) {
                                if (typeof value === 'string') {
                                    action.searchParams.set(key, value);
                                }
                            }
                            window.location.href = appOrigin + '/proxy?url=' + encodeURIComponent(action.toString());
                            return true;
                        } catch (error) {
                            console.error(error);
                            return false;
                        }
                    }

                    // Bat ca truong hop trang goi form.submit() bang JavaScript
                    const nativeSubmit = HTMLFormElement.prototype.submit;
                    HTMLFormElement.prototype.submit = function() {
                        if (!goViaProxy(this)) {
                            nativeSubmit.call(this);
                        }
                    };

                    function sendPageInfo() {
                        const data = {
                            type: 'mini-browser-page',
                            title: document.title || 'Trang web',
                            url: currentUrl
                        };

                        if (
                            window.parent &&
                            window.parent !== window
                        ) {
                            window.parent.postMessage(
                                data,
                                '*'
                            );
                        }
                    }

                    window.addEventListener(
                        'load',
                        sendPageInfo
                    );

                    document.addEventListener(
                        'click',
                        function(event) {
                            const link =
                                event.target.closest(
                                    'a[href]'
                                );

                            if (!link) {
                                return;
                            }

                            const href =
                                link.getAttribute(
                                    'href'
                                ) || '';

                            const lowerHref =
                                href.toLowerCase();

                            if (
                                href.startsWith('#') ||
                                lowerHref.startsWith(
                                    'javascript:'
                                ) ||
                                lowerHref.startsWith(
                                    'mailto:'
                                ) ||
                                lowerHref.startsWith(
                                    'tel:'
                                ) ||
                                lowerHref.startsWith(
                                    'data:'
                                )
                            ) {
                                return;
                            }

                            try {
                                const nextUrl =
                                    new URL(
                                        href,
                                        currentUrl
                                    ).toString();

                                event.preventDefault();

                                window.location.href =
                                    appOrigin + '/proxy?url=' +
                                    encodeURIComponent(
                                        nextUrl
                                    );
                            } catch (error) {
                                console.error(error);
                            }
                        },
                        true
                    );

                    document.addEventListener(
                        'submit',
                        function(event) {
                            const form =
                                event.target;

                            if (
                                !form ||
                                form.tagName !== 'FORM'
                            ) {
                                return;
                            }

                            const method =
                                (
                                    form.getAttribute(
                                        'method'
                                    ) || 'get'
                                ).toLowerCase();

                            if (method !== 'get') {
                                return;
                            }

                            event.preventDefault();

                            try {
                                const action =
                                    new URL(
                                        form.getAttribute(
                                            'action'
                                        ) || currentUrl,
                                        currentUrl
                                    );

                                const data =
                                    new FormData(form);

                                for (
                                    const [
                                        key,
                                        value
                                    ] of data.entries()
                                ) {
                                    action.searchParams.set(
                                        key,
                                        value
                                    );
                                }

                                window.location.href =
                                    appOrigin + '/proxy?url=' +
                                    encodeURIComponent(
                                        action.toString()
                                    );
                            } catch (error) {
                                console.error(error);
                            }
                        },
                        true
                    );
                })();
                </script>
                """.formatted(baseUrl, jsUrl, escapeJs(appOrigin));

        String lower =
                rewritten.toLowerCase(
                        Locale.ROOT
                );

        int headIndex =
                lower.indexOf("<head>");

        if (headIndex >= 0) {
            int insertPosition =
                    headIndex + "<head>".length();

            return rewritten.substring(
                    0,
                    insertPosition
            ) + injected + rewritten.substring(
                    insertPosition
            );
        }

        int htmlIndex =
                lower.indexOf("<html");

        if (htmlIndex >= 0) {
            int tagEnd =
                    rewritten.indexOf(
                            ">",
                            htmlIndex
                    );

            if (tagEnd >= 0) {
                return rewritten.substring(
                        0,
                        tagEnd + 1
                ) + "<head>" + injected
                        + "</head>"
                        + rewritten.substring(
                        tagEnd + 1
                );
            }
        }

        return "<!doctype html>"
                + "<html>"
                + "<head>"
                + injected
                + "</head>"
                + "<body>"
                + rewritten
                + "</body>"
                + "</html>";
    }

    private static String rewriteResourceLinks(
            String html,
            URI baseUri,
            String appOrigin) {

        Pattern pattern =
                Pattern.compile(
                        "(?i)(href|src|poster)"
                                + "\\s*=\\s*"
                                + "([\"'])(.*?)\\2"
                );

        Matcher matcher =
                pattern.matcher(html);

        StringBuffer result =
                new StringBuffer();

        while (matcher.find()) {
            String attribute =
                    matcher.group(1);

            String quote =
                    matcher.group(2);

            String rawValue =
                    matcher.group(3).trim();

            String newValue =
                    rewriteSingleLink(
                            rawValue,
                        baseUri,
                        appOrigin
                    );

            String replacement =
                    attribute
                            + "="
                            + quote
                            + newValue
                            + quote;

            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(
                            replacement
                    )
            );
        }

        matcher.appendTail(result);

        return result.toString();
    }

    private static String rewriteSingleLink(
            String raw,
            URI baseUri,
            String appOrigin) {

        if (raw == null || raw.isBlank()) {
            return raw;
        }

        String value =
                raw.trim();

        String lower =
                value.toLowerCase(
                        Locale.ROOT
                );

        if (value.startsWith("#")
                || lower.startsWith("javascript:")
                || lower.startsWith("mailto:")
                || lower.startsWith("tel:")
                || lower.startsWith("data:")
                || lower.startsWith("blob:")
                || lower.startsWith("about:")) {

            return value;
        }

        try {
            URI resolved;

            if (value.startsWith("//")) {
                resolved = URI.create(
                        baseUri.getScheme()
                                + ":"
                                + value
                );
            } else {
                resolved =
                        baseUri.resolve(value);
            }

            String scheme =
                    resolved.getScheme();

            if (scheme != null
                    && ("http".equalsIgnoreCase(
                    scheme)
                    || "https".equalsIgnoreCase(
                    scheme))) {

                return appOrigin + "/proxy?url="
                    + URLEncoder.encode(
                        resolved.toString(),
                        StandardCharsets.UTF_8
                );
            }

        } catch (Exception ignored) {
            return value;
        }

        return value;
    }

    private static String extractYoutubeVideoId(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return null;
        }

        String normalized = normalizeInput(rawInput);
        URI uri;

        try {
            uri = URI.create(normalized);
        } catch (Exception e) {
            return null;
        }

        String host = uri.getHost();
        if (host == null) {
            return null;
        }

        String hostLower = host.toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();

        if ("youtu.be".equals(hostLower)) {
            String[] parts = path.split("/");
            for (String part : parts) {
                if (!part.isBlank()) {
                    return sanitizeYoutubeId(part);
                }
            }
            return null;
        }

        if (hostLower.contains("youtube.com")) {
            if (path.startsWith("/watch")) {
                Map<String, String> query = parseQuery(uri.getRawQuery());
                return sanitizeYoutubeId(query.get("v"));
            }

            if (path.startsWith("/embed/")) {
                return sanitizeYoutubeId(path.substring("/embed/".length()));
            }

            if (path.startsWith("/shorts/")) {
                return sanitizeYoutubeId(path.substring("/shorts/".length()));
            }
        }

        return null;
    }

    private static String sanitizeYoutubeId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String cleaned = value.trim();
        int slash = cleaned.indexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(0, slash);
        }

        int question = cleaned.indexOf('?');
        if (question >= 0) {
            cleaned = cleaned.substring(0, question);
        }

        int amp = cleaned.indexOf('&');
        if (amp >= 0) {
            cleaned = cleaned.substring(0, amp);
        }

        if (!cleaned.matches("^[A-Za-z0-9_-]{6,20}$")) {
            return null;
        }
        return cleaned;
    }

    private static String extractYoutubeKeyword(String rawInput, String fallbackKeyword) {
        if (fallbackKeyword != null && !fallbackKeyword.isBlank()) {
            return fallbackKeyword.trim();
        }

        if (rawInput == null || rawInput.isBlank()) {
            return "";
        }

        String rawLower = rawInput.trim().toLowerCase(Locale.ROOT);
        if (rawLower.startsWith("yt ")) {
            String keyword = rawInput.trim().substring(3).trim();
            return keyword;
        }
        if (rawLower.startsWith("youtube ")) {
            String keyword = rawInput.trim().substring(8).trim();
            return keyword;
        }

        String normalized = normalizeInput(rawInput);
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (Exception e) {
            return "";
        }

        String host = uri.getHost();
        if (host == null) {
            return "";
        }

        String hostLower = host.toLowerCase(Locale.ROOT);
        if (!hostLower.contains("youtube.com") && !"youtu.be".equals(hostLower)) {
            return "";
        }

        Map<String, String> q = parseQuery(uri.getRawQuery());
        String keyword = q.get("search_query");
        if (keyword == null || keyword.isBlank()) {
            keyword = q.get("q");
        }
        if (keyword == null || keyword.isBlank()) {
            return "";
        }
        return keyword.trim();
    }

    private static boolean isYoutubeVideoUrl(String host, String path) {
        if (host == null) {
            return false;
        }

        String lowerHost = host.toLowerCase(Locale.ROOT);
        if ("youtu.be".equals(lowerHost)) {
            return true;
        }

        if (!lowerHost.contains("youtube.com")) {
            return false;
        }

        return path.startsWith("/watch")
                || path.startsWith("/embed/")
                || path.startsWith("/shorts/");
    }

    private static void sendRedirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static String buildYouTubeVideoPage(String videoId) {
        String safeId = escapeHtml(videoId);
        return """
<!DOCTYPE html>
<html lang="vi">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>YouTube Player</title>
  <style>
    html, body {
      margin: 0;
      width: 100%%;
      height: 100%%;
      background: #000;
      overflow: hidden;
    }
    iframe {
      width: 100%%;
      height: 100%%;
      border: none;
      display: block;
    }
  </style>
</head>
<body>
  <iframe
    src="https://www.youtube-nocookie.com/embed/%s?autoplay=1&rel=0"
    title="YouTube video player"
    referrerpolicy="strict-origin-when-cross-origin"
    allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
    allowfullscreen>
  </iframe>
  <script>
    window.parent?.postMessage({
      type: 'mini-browser-page',
      title: 'YouTube - Dang phat video',
      url: 'https://www.youtube.com/watch?v=%s'
    }, '*');
  </script>
</body>
</html>
""".formatted(safeId, safeId);
    }

    private static String buildYouTubeSearchPage(String keyword, String sourceUrl) {
        String cleanKeyword = keyword == null ? "" : keyword.trim();

        String page = """
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>YouTube</title>
    <style>
        * { box-sizing: border-box; }
        html, body { margin: 0; width: 100%; height: 100%; background: #0f0f0f; color: #fff; font-family: "Segoe UI", Arial, sans-serif; }
        body { display: flex; flex-direction: column; }
        .bar { flex: none; display: flex; align-items: center; gap: 8px; padding: 8px 10px; background: #1f1f1f; }
        .bar input { flex: 1; min-width: 0; height: 36px; border-radius: 8px; border: 1px solid #3a3a3a; background: #111; color: #fff; padding: 0 10px; font-size: 14px; }
        .bar input:focus { outline: 2px solid #cc0000; }
        button { height: 36px; border: none; border-radius: 8px; background: #cc0000; color: #fff; padding: 0 14px; cursor: pointer; font-size: 14px; }
        button:hover { background: #e00000; }
        button.secondary { background: #333; }
        button.secondary:hover { background: #444; }
        .content { flex: 1; min-height: 0; overflow-y: auto; padding: 14px; }
        .message { padding: 18px; border-radius: 10px; background: #1a1a1a; color: #d0d0d0; line-height: 1.6; }
        .message.error { background: #2b1414; color: #ffb4b4; border: 1px solid #5a2323; }
        .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 14px; }
        .card { display: block; width: 100%; height: auto; padding: 0; text-align: left; background: #181818; border-radius: 10px; overflow: hidden; color: #fff; }
        .card:hover { background: #242424; }
        .card img { display: block; width: 100%; aspect-ratio: 16 / 9; object-fit: cover; background: #000; }
        .card .info { padding: 10px 12px 12px; }
        .card .title { font-size: 14px; font-weight: 600; line-height: 1.35; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
        .card .channel { margin-top: 6px; font-size: 12px; color: #aaa; }
        .player-wrap { display: flex; flex-direction: column; height: 100%; gap: 10px; }
        .player-wrap iframe { flex: 1; min-height: 300px; width: 100%; border: none; background: #000; border-radius: 10px; }
        .player-title { font-size: 15px; font-weight: 600; }
        [hidden] { display: none !important; }
    </style>
</head>
<body>
    <div class="bar">
        <input id="q" value="__KEYWORD__" placeholder="Tim tren YouTube hoac dan link video" autocomplete="off">
        <button id="searchBtn">Search</button>
        <button id="homeBtn" class="secondary">Home</button>
    </div>

    <div class="content">
        <div id="message" class="message" hidden></div>
        <div id="results" class="grid" hidden></div>
        <div id="playerView" class="player-wrap" hidden>
            <div>
                <button id="backBtn" class="secondary">Quay lai ket qua</button>
            </div>
            <div id="playerTitle" class="player-title"></div>
            <iframe id="player"
                    title="YouTube video player"
                    referrerpolicy="strict-origin-when-cross-origin"
                    allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
                    allowfullscreen></iframe>
        </div>
    </div>

    <script>
        const appOrigin = window.location.origin;
        const qInput = document.getElementById('q');
        const messageBox = document.getElementById('message');
        const resultsBox = document.getElementById('results');
        const playerView = document.getElementById('playerView');
        const playerFrame = document.getElementById('player');
        const playerTitle = document.getElementById('playerTitle');
        let hasResults = false;

        function notifyParent(title, url) {
            window.parent?.postMessage({ type: 'mini-browser-page', title: title, url: url }, '*');
        }

        function decodeEntities(text) {
            const area = document.createElement('textarea');
            area.innerHTML = text || '';
            return area.value;
        }

        function show(which) {
            messageBox.hidden = which !== 'message';
            resultsBox.hidden = which !== 'results';
            playerView.hidden = which !== 'player';
        }

        function showMessage(text, isError) {
            messageBox.textContent = text;
            messageBox.className = 'message' + (isError ? ' error' : '');
            show('message');
        }

        function parseVideoId(text) {
            const value = (text || '').trim();
            try {
                const u = new URL(value);
                const host = u.hostname.toLowerCase();
                if (host === 'youtu.be') {
                    return u.pathname.split('/').filter(Boolean)[0] || null;
                }
                if (host.includes('youtube.com')) {
                    if (u.pathname === '/watch') {
                        return u.searchParams.get('v');
                    }
                    const parts = u.pathname.split('/').filter(Boolean);
                    if (parts[0] === 'embed' || parts[0] === 'shorts') {
                        return parts[1] || null;
                    }
                }
            } catch (e) {
                return null;
            }
            return null;
        }

        function play(id, title) {
            playerFrame.src = 'https://www.youtube-nocookie.com/embed/'
                + encodeURIComponent(id) + '?autoplay=1&rel=0';
            playerTitle.textContent = title || '';
            show('player');
            notifyParent('YouTube - ' + (title || 'Dang phat video'),
                'https://www.youtube.com/watch?v=' + id);
        }

        function backToResults() {
            playerFrame.src = 'about:blank';
            if (hasResults) {
                show('results');
            } else {
                showMessage('Nhap tu khoa de tim video.');
            }
        }

        function renderResults(items) {
            resultsBox.replaceChildren();
            for (const item of items) {
                const id = item.id && item.id.videoId;
                const sn = item.snippet || {};
                if (!id) {
                    continue;
                }
                const title = decodeEntities(sn.title);
                const thumbs = sn.thumbnails || {};
                const thumb = (thumbs.medium || thumbs.high || thumbs.default || {}).url || '';

                const card = document.createElement('button');
                card.className = 'card';
                card.type = 'button';

                const img = document.createElement('img');
                img.src = thumb;
                img.alt = '';
                img.loading = 'lazy';
                img.referrerPolicy = 'no-referrer';

                const info = document.createElement('div');
                info.className = 'info';
                const t = document.createElement('div');
                t.className = 'title';
                t.textContent = title;
                const c = document.createElement('div');
                c.className = 'channel';
                c.textContent = decodeEntities(sn.channelTitle);
                info.append(t, c);

                card.append(img, info);
                card.onclick = () => play(id, title);
                resultsBox.appendChild(card);
            }
        }

        async function search(text) {
            const query = (text || '').trim();

            if (!query) {
                hasResults = false;
                showMessage('Nhap tu khoa de tim video, hoac dan link YouTube vao o tim kiem.');
                notifyParent('YouTube', 'https://www.youtube.com');
                return;
            }

            const id = parseVideoId(query);
            if (id) {
                play(id, query);
                return;
            }

            showMessage('Dang tim: ' + query);

            try {
                const res = await fetch(appOrigin + '/ytapi?q=' + encodeURIComponent(query));
                const data = await res.json();

                if (!res.ok || data.error) {
                    const detail = data.error && data.error.message
                        ? data.error.message
                        : 'Ma loi ' + res.status;
                    throw new Error(detail);
                }

                const items = data.items || [];
                if (items.length === 0) {
                    hasResults = false;
                    showMessage('Khong tim thay video nao cho: ' + query);
                    return;
                }

                renderResults(items);
                hasResults = true;
                show('results');
                notifyParent('YouTube - ' + query,
                    'https://www.youtube.com/results?search_query=' + encodeURIComponent(query));
            } catch (err) {
                hasResults = false;
                showMessage('Khong tim kiem duoc: ' + err.message, true);
            }
        }

        document.getElementById('searchBtn').onclick = () => search(qInput.value);
        document.getElementById('homeBtn').onclick = () => { qInput.value = ''; search(''); };
        document.getElementById('backBtn').onclick = backToResults;
        qInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                search(qInput.value);
            }
        });

        search(qInput.value);
    </script>
</body>
</html>
""";

        return page.replace("__KEYWORD__", escapeHtml(cleanKeyword));
    }

    private static String escapeHtml(
            String value) {

        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String escapeJs(
            String value) {

        return value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    // ============================================================
    // RESPONSE
    // ============================================================

    private static void sendText(
            HttpExchange exchange,
            int statusCode,
            String message) throws IOException {

        byte[] data =
                message.getBytes(
                        StandardCharsets.UTF_8
                );

        exchange.getResponseHeaders().set(
                "Content-Type",
                "text/plain; charset=UTF-8"
        );

        exchange.sendResponseHeaders(
                statusCode,
                data.length
        );

        try (OutputStream output =
                     exchange.getResponseBody()) {
            output.write(data);
        }
    }

    private static void sendBytes(
            HttpExchange exchange,
            int statusCode,
            String contentType,
            byte[] data) throws IOException {

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(statusCode, data.length);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(data);
        }
    }

    private static void sendJson(
            HttpExchange exchange,
            int statusCode,
            String json) throws IOException {

        sendBytes(exchange, statusCode,
                "application/json; charset=UTF-8",
                json.getBytes(StandardCharsets.UTF_8));
    }

    private static String errorJson(String message) {
        String safe = message
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
        return "{\"error\":{\"message\":\"" + safe + "\"}}";
    }

    // ============================================================
    // HOME PAGE
    // ============================================================

    private static String buildHomePage() {
        return """
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport"
          content="width=device-width, initial-scale=1.0">

    <title>Mini Coc Coc</title>

    <style>
        :root {
            --primary: #2563eb;
            --primary-dark: #1d4ed8;
            --background: #eef4ff;
            --surface: rgba(255,255,255,.88);
            --surface-solid: #ffffff;
            --text: #172033;
            --muted: #64748b;
            --border: #dbe4f0;
            --danger: #dc2626;
            --success: #16a34a;
            --shadow: 0 15px 40px rgba(30,64,175,.14);
            --radius: 18px;
        }

        * {
            box-sizing: border-box;
        }

        body {
            margin: 0;
            min-height: 100vh;
            color: var(--text);
            font-family: Inter, "Segoe UI", Arial, sans-serif;

            background:
                radial-gradient(
                    circle at top left,
                    #dbeafe 0,
                    transparent 34%
                ),
                radial-gradient(
                    circle at bottom right,
                    #e0e7ff 0,
                    transparent 35%
                ),
                var(--background);

            display: grid;
            grid-template-rows: auto auto 1fr;
        }

        button,
        input {
            font-family: inherit;
        }

        button {
            border: 1px solid var(--border);
            border-radius: 12px;
            background: var(--surface-solid);
            color: var(--text);
            cursor: pointer;
            transition: .2s ease;
        }

        button:hover {
            color: var(--primary);
            border-color: var(--primary);
            transform: translateY(-1px);
            box-shadow:
                0 6px 16px rgba(37,99,235,.18);
        }

        button:active {
            transform: scale(.97);
        }

        button:disabled {
            opacity: .45;
            cursor: not-allowed;
            transform: none;
            box-shadow: none;
        }

        .toolbar {
            display: flex;
            align-items: center;
            gap: 9px;
            padding: 13px 18px;
            background: var(--surface);
            border-bottom: 1px solid var(--border);
            box-shadow: 0 4px 20px rgba(15,23,42,.08);
            backdrop-filter: blur(16px);
        }

        .brand {
            display: flex;
            align-items: center;
            gap: 9px;
            min-width: 175px;
            font-size: 19px;
            font-weight: 800;
            color: var(--primary);
            white-space: nowrap;
        }

        .brand-icon {
            width: 34px;
            height: 34px;
            display: grid;
            place-items: center;
            color: white;
            border-radius: 12px;
            background:
                linear-gradient(
                    135deg,
                    #16a34a,
                    #22c55e
                );
            box-shadow:
                0 6px 14px rgba(22,163,74,.3);
            overflow: hidden;
        }

        .brand-icon svg {
            width: 23px;
            height: 23px;
        }

        .icon-button {
            width: 40px;
            height: 40px;
            font-size: 17px;
            display: grid;
            place-items: center;
        }

        .address-wrapper {
            flex: 1;
            display: flex;
            align-items: center;
            gap: 8px;
            min-width: 180px;
        }

        .address {
            width: 100%;
            height: 42px;
            padding: 0 17px;
            border: 1px solid var(--border);
            border-radius: 999px;
            outline: none;
            color: var(--text);
            background: #f8fafc;
            font-size: 14px;
        }

        .address:focus {
            border-color: var(--primary);
            box-shadow:
                0 0 0 4px rgba(37,99,235,.12);
        }

        .go-button {
            height: 42px;
            padding: 0 18px;
            color: white;
            font-weight: 700;
            border: none;
            background:
                linear-gradient(
                    135deg,
                    var(--primary),
                    var(--primary-dark)
                );
        }

        .go-button:hover {
            color: white;
            border: none;
        }

        .tabs {
            display: flex;
            align-items: center;
            gap: 8px;
            min-height: 58px;
            padding: 9px 18px;
            overflow-x: auto;
            background: rgba(255,255,255,.62);
            border-bottom: 1px solid var(--border);
        }

        .tab {
            display: inline-flex;
            align-items: center;
            gap: 9px;
            max-width: 245px;
            padding: 9px 12px;
            border: 1px solid var(--border);
            border-radius: 999px;
            background: white;
            cursor: pointer;
            user-select: none;
            box-shadow: 0 3px 10px rgba(15,23,42,.05);
        }

        .tab.active {
            color: var(--primary);
            border-color: var(--primary);
            background: #eff6ff;
            box-shadow:
                inset 0 0 0 1px var(--primary);
        }

        .tab-name {
            max-width: 185px;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
            font-size: 13px;
            font-weight: 600;
        }

        .tab-close {
            width: 23px;
            height: 23px;
            padding: 0;
            border: none;
            color: var(--muted);
            background: transparent;
            box-shadow: none;
        }

        .tab-close:hover {
            color: var(--danger);
            border: none;
            box-shadow: none;
        }

        .workspace {
            display: grid;
            grid-template-columns: 290px minmax(0, 1fr);
            gap: 18px;
            min-height: 0;
            padding: 18px;
        }

        .sidebar,
        .viewer {
            min-height: 0;
            border: 1px solid var(--border);
            border-radius: var(--radius);
            background: var(--surface);
            box-shadow: var(--shadow);
            backdrop-filter: blur(16px);
        }

        .sidebar {
            padding: 16px;
            overflow-y: auto;
        }

        .section-title {
            display: flex;
            align-items: center;
            justify-content: space-between;
            margin: 8px 0 11px;
            color: var(--primary-dark);
            font-size: 13px;
            font-weight: 800;
            letter-spacing: .5px;
            text-transform: uppercase;
        }

        .quick-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 9px;
            margin-bottom: 22px;
        }

        .quick {
            min-height: 65px;
            padding: 10px;
            text-align: left;
            font-size: 13px;
            font-weight: 600;
            background: white;
        }

        .quick-icon {
            display: block;
            margin-bottom: 5px;
            font-size: 20px;
        }

        .list {
            display: grid;
            gap: 9px;
            margin-bottom: 22px;
        }

        .empty {
            padding: 15px 10px;
            color: var(--muted);
            border: 1px dashed var(--border);
            border-radius: 12px;
            text-align: center;
            font-size: 13px;
        }

        .item {
            padding: 10px;
            border: 1px solid var(--border);
            border-radius: 13px;
            background: white;
        }

        .item-title {
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
            color: var(--text);
            font-size: 13px;
            font-weight: 700;
            cursor: pointer;
        }

        .item-title:hover {
            color: var(--primary);
        }

        .item-url {
            margin-top: 5px;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
            color: var(--muted);
            font-size: 11px;
            cursor: pointer;
        }

        .item-actions {
            display: flex;
            justify-content: flex-end;
            margin-top: 8px;
        }

        .remove-button {
            padding: 5px 8px;
            color: var(--danger);
            border-color: #fecaca;
            font-size: 11px;
        }

        .viewer {
            display: grid;
            grid-template-rows: minmax(0, 1fr) auto;
            overflow: hidden;
            background: white;
        }

        iframe {
            width: 100%;
            height: 100%;
            min-height: 420px;
            border: none;
            background: white;
        }

        .status {
            min-height: 39px;
            padding: 11px 15px;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
            color: var(--muted);
            border-top: 1px solid var(--border);
            background: #f8fafc;
            font-size: 12px;
        }

        @media (max-width: 1050px) {
            .brand {
                min-width: auto;
            }

            .brand-text {
                display: none;
            }

            .workspace {
                grid-template-columns: 235px minmax(0, 1fr);
            }
        }

        @media (max-width: 760px) {
            .toolbar {
                flex-wrap: wrap;
                padding: 10px;
            }

            .brand {
                width: 100%;
            }

            .address-wrapper {
                width: 100%;
            }

            .workspace {
                grid-template-columns: 1fr;
                padding: 10px;
            }

            .sidebar {
                order: 2;
                max-height: 300px;
            }

            .viewer {
                order: 1;
                min-height: 65vh;
            }

            iframe {
                min-height: 55vh;
            }
        }
    </style>
</head>

<body>
    <header class="toolbar">
        <div class="brand">
            <div class="brand-icon" aria-label="logo mini coc coc">
                <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <circle cx="12" cy="12" r="9.2" fill="white" opacity="0.95"/>
                    <path d="M16.7 8.8c-.9-1.1-2.3-1.8-3.9-1.8-2.8 0-5 2.2-5 5s2.2 5 5 5c1.8 0 3.4-1 4.2-2.5h-2.4c-.5.4-1.1.6-1.8.6-1.7 0-3.1-1.4-3.1-3.1S11.1 9 12.8 9c.8 0 1.6.3 2.1.8l1.8-1z" fill="#16a34a"/>
                    <circle cx="12.8" cy="12" r="2" fill="#22c55e"/>
                </svg>
            </div>
            <span class="brand-text">Mini Coc Coc</span>
        </div>

          <button id="backButton"
                class="icon-button"
                title="Quay lai">
            <
        </button>

        <button id="forwardButton"
                class="icon-button"
                title="Di toi">
            >
        </button>

        <button id="reloadButton"
                class="icon-button"
                title="Tai lai">
            <svg xmlns="http://www.w3.org/2000/svg" width="22" height="22" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <path d="M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/>
            </svg>
        </button>

        <button id="homeButton"
                class="icon-button"
                title="Trang chu">
            <svg xmlns="http://www.w3.org/2000/svg" width="22" height="22" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <path d="M 12 2.0996094 L 1 12 L 4 12 L 4 21 L 11 21 L 11 15 L 13 15 L 13 21 L 20 21 L 20 12 L 23 12 L 12 2.0996094 z M 12 4.7910156 L 18 10.191406 L 18 11 L 18 19 L 15 19 L 15 13 L 9 13 L 9 19 L 6 19 L 6 10.191406 L 12 4.7910156 z"/>
            </svg>
        </button>

        <div class="address-wrapper">
            <input id="addressInput"
                   class="address"
                   autocomplete="off"
                   placeholder="Nhap dia chi website hoac tu khoa tim kiem...">

            <button id="goButton"
                    class="go-button"
                    title="Tim kiem / Di toi">
                <img width="24" height="24"
                     src="https://img.icons8.com/fluency-systems-regular/24/search--v1.png"
                     alt="Tim kiem"
                     style="display:block; filter: brightness(0) invert(1);">
            </button>
        </div>

               <button id="bookmarkButton"
                class="icon-button"
                title="Luu trang">
            <img width="24" height="24"
                 src="https://img.icons8.com/material-two-tone/24/star--v2.png"
                 alt="Luu trang"
                 style="display:block;">
        </button>

        <button id="newTabButton"
                class="icon-button"
                title="Tab moi">
            <img width="24" height="24"
                 src="https://img.icons8.com/android/24/plus.png"
                 alt="Tab moi"
                 style="display:block;">
        </button>
    </header>

    <div id="tabs" class="tabs"></div>

    <main class="workspace">
        <aside class="sidebar">
            <div class="section-title">
                <span>Trang nhanh</span>
            </div>

            <div id="quickSites"
                 class="quick-grid"></div>

            <div class="section-title">
                <span>Bookmark</span>
            </div>

            <div id="bookmarkList"
                 class="list"></div>

            <div class="section-title">
                <span>Lich su</span>
            </div>

            <div id="historyList"
                 class="list"></div>
        </aside>

        <section class="viewer">
            <iframe id="viewerFrame"></iframe>
            <div id="status"
                 class="status">
                San sang.
            </div>
        </section>
    </main>

    <script>
        const HOME =
            "https://www.coccoc.com";

        const SEARCH =
            "https://duckduckgo.com/?q=";

        const frame =
            document.getElementById("viewerFrame");

        const addressInput =
            document.getElementById("addressInput");

        const tabsElement =
            document.getElementById("tabs");

        const statusElement =
            document.getElementById("status");

        const quickSites = [
            {
                name: "Coc Coc",
                icon: "CC",
                url: "https://www.coccoc.com"
            },
            {
                name: "Google",
                icon: "G",
                url: "https://www.google.com"
            },
            {
                name: "YouTube",
                icon: "YT",
                url: "https://www.youtube.com"
            },
            {
                name: "Wikipedia",
                icon: "W",
                url: "https://vi.wikipedia.org"
            },
            {
                name: "GitHub",
                icon: "GH",
                url: "https://github.com"
            },
            {
                name: "VNExpress",
                icon: "VN",
                url: "https://vnexpress.net"
            }
        ];

        const state = {
            tabs: [],
            activeTabId: null
        };

        function createId() {
            return Date.now().toString(36)
                + Math.random()
                    .toString(36)
                    .substring(2, 8);
        }

        function getActiveTab() {
            return state.tabs.find(
                tab => tab.id === state.activeTabId
            );
        }

        function normalizeInput(input) {
            const text =
                (input || "").trim();

            if (!text) {
                return HOME;
            }

            if (/^[a-zA-Z][a-zA-Z0-9+.-]*:\\/\\//
                .test(text)) {
                return text;
            }

            const lower = text.toLowerCase();
            if (lower.startsWith("yt ") || lower.startsWith("youtube ")) {
                const keyword = text.substring(text.indexOf(" ") + 1).trim();
                if (keyword) {
                    return "https://www.youtube.com/results?search_query="
                        + encodeURIComponent(keyword);
                }
            }

            if (
                /^(localhost|127\\.0\\.0\\.1|\\d{1,3}(\\.\\d{1,3}){3})/
                    .test(text)
            ) {
                return "http://" + text;
            }

            if (text.includes(" ")) {
                return SEARCH
                    + encodeURIComponent(text);
            }

            if (text.includes(".")) {
                return "https://" + text;
            }

            return SEARCH
                + encodeURIComponent(text);
        }

        function parseYouTubeVideoId(url) {
            if (!url) {
                return null;
            }

            try {
                const u = new URL(url);
                const host = u.hostname.toLowerCase();

                if (host === "youtu.be") {
                    const id = u.pathname.split("/").filter(Boolean)[0];
                    return sanitizeVideoId(id);
                }

                if (host.includes("youtube.com")) {
                    if (u.pathname.startsWith("/watch")) {
                        return sanitizeVideoId(u.searchParams.get("v"));
                    }
                    if (u.pathname.startsWith("/embed/")) {
                        return sanitizeVideoId(u.pathname.slice(7));
                    }
                    if (u.pathname.startsWith("/shorts/")) {
                        return sanitizeVideoId(u.pathname.slice(8));
                    }
                }
            } catch {
                return null;
            }

            return null;
        }

        function sanitizeVideoId(value) {
            if (!value) {
                return null;
            }

            const clean = value.split("/")[0].split("?")[0].split("&")[0].trim();
            return /^[A-Za-z0-9_-]{6,20}$/.test(clean)
                ? clean
                : null;
        }

        function buildFrameSrc(url) {
                    try {
                const s = new URL(url);
                const sh = s.hostname.toLowerCase();
                const sq = s.searchParams.get("q");
                if (sq && (
                    (sh.endsWith("google.com") && s.pathname === "/search") ||
                    (sh.endsWith("bing.com") && s.pathname === "/search") ||
                    (sh.endsWith("duckduckgo.com") && (s.pathname === "/" || s.pathname === "/html/"))
                )) {
                    return "/search?q=" + encodeURIComponent(sq);
                }
            } catch {
                // khong phai URL day du
            }
            try {
                const u = new URL(url);
                const host = u.hostname.toLowerCase();
                if (host.includes("youtube.com") || host === "youtu.be") {
                    return "/youtube?url=" + encodeURIComponent(url);
                }
            } catch {
                // Fallback to other handlers.
            }

            const youtubeId = parseYouTubeVideoId(url);
            if (youtubeId) {
                return "/youtube?id=" + encodeURIComponent(youtubeId);
            }
            return "/proxy?url=" + encodeURIComponent(url);
        }

        function updateStatus(message) {
            statusElement.textContent = message;
        }

        function loadBookmarks() {
            try {
                return JSON.parse(
                    localStorage.getItem(
                        "miniBrowserBookmarks"
                    ) || "[]"
                );
            } catch {
                return [];
            }
        }

        function saveBookmarks(list) {
            localStorage.setItem(
                "miniBrowserBookmarks",
                JSON.stringify(list)
            );
        }

        function loadHistory() {
            try {
                return JSON.parse(
                    localStorage.getItem(
                        "miniBrowserHistory"
                    ) || "[]"
                );
            } catch {
                return [];
            }
        }

        function saveHistory(list) {
            localStorage.setItem(
                "miniBrowserHistory",
                JSON.stringify(list)
            );
        }

        function addHistory(title, url) {
            const oldList =
                loadHistory();

            const filtered =
                oldList.filter(
                    item => item.url !== url
                );

            filtered.unshift({
                title: title || url,
                url: url,
                time: new Date()
                    .toLocaleString("vi-VN")
            });

            saveHistory(
                filtered.slice(0, 30)
            );

            renderHistory();
        }

        function addBookmark(title, url) {
            const list =
                loadBookmarks();

            const existed =
                list.some(item => item.url === url);

            if (!existed) {
                list.unshift({
                    title: title || url,
                    url: url
                });

                saveBookmarks(
                    list.slice(0, 30)
                );

                updateStatus(
                    "Da luu bookmark"
                );
            } else {
                updateStatus(
                    "Trang nay da co trong bookmark"
                );
            }

            renderBookmarks();
        }

        function removeBookmark(url) {
            const list =
                loadBookmarks().filter(
                    item => item.url !== url
                );

            saveBookmarks(list);
            renderBookmarks();
            updateStatus("Da xoa bookmark");
        }

        function newTab(url = HOME) {
            const tab = {
                id: createId(),
                title: "Tab moi",
                history: [],
                index: -1
            };

            state.tabs.push(tab);
            state.activeTabId = tab.id;

            renderTabs();
            navigate(url, false);
        }

        function closeTab(id) {
            const index =
                state.tabs.findIndex(
                    tab => tab.id === id
                );

            if (index < 0) {
                return;
            }

            const wasActive =
                state.activeTabId === id;

            state.tabs.splice(index, 1);

            if (state.tabs.length === 0) {
                newTab(HOME);
                return;
            }

            if (wasActive) {
                const nextIndex =
                    Math.max(0, index - 1);

                state.activeTabId =
                    state.tabs[nextIndex].id;

                showCurrentTab();
            }

            renderTabs();
        }

        function switchTab(id) {
            state.activeTabId = id;
            showCurrentTab();
            renderTabs();
        }

        function showCurrentTab() {
            const tab =
                getActiveTab();

            if (!tab) {
                return;
            }

            const url =
                tab.history[tab.index] || HOME;

            addressInput.value = url;

            frame.src = buildFrameSrc(url);

            updateStatus(
                "Dang mo: " + url
            );

            updateNavigationButtons();
        }

        function navigate(input, fromHistory) {
            const tab =
                getActiveTab();

            if (!tab) {
                return;
            }

            const url =
                normalizeInput(input);

            if (!fromHistory) {
                tab.history =
                    tab.history.slice(
                        0,
                        tab.index + 1
                    );

                tab.history.push(url);
                tab.index =
                    tab.history.length - 1;
            }

            addressInput.value = url;

            frame.src = buildFrameSrc(url);

            updateStatus(
                "Dang tai: " + url
            );

            updateNavigationButtons();
            renderTabs();
        }

        function goBack() {
            const tab =
                getActiveTab();

            if (!tab || tab.index <= 0) {
                return;
            }

            tab.index--;
            navigate(
                tab.history[tab.index],
                true
            );
        }

        function goForward() {
            const tab =
                getActiveTab();

            if (
                !tab ||
                tab.index + 1 >= tab.history.length
            ) {
                return;
            }

            tab.index++;
            navigate(
                tab.history[tab.index],
                true
            );
        }

        function reloadPage() {
            const tab =
                getActiveTab();

            if (!tab || tab.index < 0) {
                return;
            }

            const url =
                tab.history[tab.index];

            frame.src = "";

            setTimeout(() => {
                frame.src = buildFrameSrc(url);
            }, 30);

            updateStatus(
                "Dang tai lai: " + url
            );
        }

        function updateNavigationButtons() {
            const tab =
                getActiveTab();

            document.getElementById(
                "backButton"
            ).disabled =
                !tab || tab.index <= 0;

            document.getElementById(
                "forwardButton"
            ).disabled =
                !tab ||
                tab.index + 1 >= tab.history.length;
        }

        function renderTabs() {
            tabsElement.innerHTML = "";

            for (const tab of state.tabs) {
                const element =
                    document.createElement("div");

                element.className =
                    "tab"
                    + (
                        tab.id === state.activeTabId
                            ? " active"
                            : ""
                    );

                const title =
                    document.createElement("div");

                title.className =
                    "tab-name";

                title.textContent =
                    tab.title || "Tab";

                title.title =
                    tab.title || "Tab";

                const close =
                    document.createElement("button");

                close.className =
                    "tab-close";

                close.textContent =
                    "x";

                close.title =
                    "Dong tab";

                close.onclick =
                    function(event) {
                        event.stopPropagation();
                        closeTab(tab.id);
                    };

                element.appendChild(title);
                element.appendChild(close);

                element.onclick =
                    () => switchTab(tab.id);

                tabsElement.appendChild(element);
            }
        }

        function renderQuickSites() {
            const wrapper =
                document.getElementById(
                    "quickSites"
                );

            wrapper.innerHTML = "";

            for (const site of quickSites) {
                const button =
                    document.createElement("button");

                button.className =
                    "quick";

                button.innerHTML =
                    "<span class='quick-icon'>"
                    + site.icon
                    + "</span>"
                    + site.name;

                button.onclick =
                    () => navigate(
                        site.url,
                        false
                    );

                wrapper.appendChild(button);
            }
        }

        function renderBookmarks() {
            const wrapper =
                document.getElementById(
                    "bookmarkList"
                );

            wrapper.innerHTML = "";

            const list =
                loadBookmarks();

            if (list.length === 0) {
                wrapper.innerHTML =
                    "<div class='empty'>"
                    + "Chua co bookmark"
                    + "</div>";

                return;
            }

            for (const item of list) {
                const row =
                    document.createElement("div");

                row.className =
                    "item";

                const title =
                    document.createElement("div");

                title.className =
                    "item-title";

                title.textContent =
                    item.title;

                title.onclick =
                    () => navigate(
                        item.url,
                        false
                    );

                const url =
                    document.createElement("div");

                url.className =
                    "item-url";

                url.textContent =
                    item.url;

                url.onclick =
                    () => navigate(
                        item.url,
                        false
                    );

                const actions =
                    document.createElement("div");

                actions.className =
                    "item-actions";

                const remove =
                    document.createElement("button");

                remove.className =
                    "remove-button";

                remove.textContent =
                    "Xoa";

                remove.onclick =
                    () => removeBookmark(
                        item.url
                    );

                actions.appendChild(remove);

                row.appendChild(title);
                row.appendChild(url);
                row.appendChild(actions);

                wrapper.appendChild(row);
            }
        }

        function renderHistory() {
            const wrapper =
                document.getElementById(
                    "historyList"
                );

            wrapper.innerHTML = "";

            const list =
                loadHistory();

            if (list.length === 0) {
                wrapper.innerHTML =
                    "<div class='empty'>"
                    + "Chua co lich su"
                    + "</div>";

                return;
            }

            for (const item of list) {
                const row =
                    document.createElement("div");

                row.className =
                    "item";

                const title =
                    document.createElement("div");

                title.className =
                    "item-title";

                title.textContent =
                    item.title;

                title.onclick =
                    () => navigate(
                        item.url,
                        false
                    );

                const url =
                    document.createElement("div");

                url.className =
                    "item-url";

                url.textContent =
                    item.url
                    + " - "
                    + item.time;

                url.onclick =
                    () => navigate(
                        item.url,
                        false
                    );

                row.appendChild(title);
                row.appendChild(url);

                wrapper.appendChild(row);
            }
        }

        document.getElementById(
            "goButton"
        ).onclick = function() {
            navigate(
                addressInput.value,
                false
            );
        };

        addressInput.addEventListener(
            "keydown",
            function(event) {
                if (event.key === "Enter") {
                    navigate(
                        addressInput.value,
                        false
                    );
                }
            }
        );

        document.getElementById(
            "backButton"
        ).onclick = goBack;

        document.getElementById(
            "forwardButton"
        ).onclick = goForward;

        document.getElementById(
            "reloadButton"
        ).onclick = reloadPage;

        document.getElementById(
            "homeButton"
        ).onclick = function() {
            navigate(HOME, false);
        };

        document.getElementById(
            "newTabButton"
        ).onclick = function() {
            newTab(HOME);
        };

        document.getElementById(
            "bookmarkButton"
        ).onclick = function() {
            const tab =
                getActiveTab();

            if (!tab || tab.index < 0) {
                return;
            }

            const url =
                tab.history[tab.index];

            addBookmark(
                tab.title || url,
                url
            );
        };

        frame.addEventListener(
            "load",
            function() {
                const tab =
                    getActiveTab();

                if (!tab || tab.index < 0) {
                    return;
                }

                const url =
                    tab.history[tab.index];

                addHistory(
                    tab.title || url,
                    url
                );

                updateStatus(
                    "Da tai xong: " + url
                );
            }
        );

        window.addEventListener(
            "message",
            function(event) {
                const data =
                    event.data || {};

                if (
                    data.type !==
                    "mini-browser-page"
                ) {
                    return;
                }

                const tab =
                    getActiveTab();

                if (!tab) {
                    return;
                }

                tab.title =
                    (
                        data.title
                        || tab.title
                        || "Tab"
                    ).substring(0, 80);

                renderTabs();
            }
        );

        renderQuickSites();
        renderBookmarks();
        renderHistory();
        newTab(HOME);
    </script>
</body>
</html>
""";
    }
}