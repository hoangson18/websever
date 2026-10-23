import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trang tim kiem cho Mini Coc Coc.
 * Dang ky trong WebClient: server.createContext("/search", new SearchHandler());
 * Lay ket qua tu DuckDuckGo (du phong: Bing) roi hien thanh trang ket qua gon.
 */
class SearchHandler implements HttpHandler {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendBytes(exchange, 405, "text/plain; charset=UTF-8",
                    "Method Not Allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }

        Map<String, String> query =
                parseQuery(exchange.getRequestURI().getRawQuery());
        String q = query.getOrDefault("q", "").trim();

        SearchOutcome outcome =
                new SearchOutcome("", java.util.List.of(), null);
        if (!q.isEmpty()) {
            outcome = runSearch(q);
        }

        sendBytes(exchange, 200, "text/html; charset=UTF-8",
                buildSearchPage(q, outcome).getBytes(StandardCharsets.UTF_8));
    }

    private static final String DDG_BASE =
            System.getenv().getOrDefault(
                    "DDG_BASE",
                    "https://html.duckduckgo.com/html/");

    private static final String BING_BASE =
            System.getenv().getOrDefault(
                    "BING_BASE",
                    "https://www.bing.com/search");

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0 Safari/537.36";

    private record SearchResult(String title, String url, String snippet) {
    }

    private record SearchOutcome(
            String provider,
            java.util.List<SearchResult> results,
            String error) {
    }

    /** Thu DuckDuckGo truoc, neu khong duoc thi thu Bing. */
    private static SearchOutcome runSearch(String q) {
        StringBuilder errors = new StringBuilder();

        for (String provider : new String[]{"DuckDuckGo", "Bing"}) {
            try {
                java.util.List<SearchResult> list =
                        "DuckDuckGo".equals(provider)
                                ? searchDuckDuckGo(q)
                                : searchBing(q);

                if (!list.isEmpty()) {
                    return new SearchOutcome(provider, list, null);
                }
                errors.append(provider)
                        .append(": khong co ket qua (co the bi chan tam thoi). ");
            } catch (Exception e) {
                String why = e.getMessage() == null
                        ? e.getClass().getSimpleName()
                        : e.getMessage();
                errors.append(provider).append(": ").append(why).append(". ");
            }
        }

        return new SearchOutcome(
                "", java.util.List.of(), errors.toString().trim());
    }

    private static String fetchSearchHtml(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8")
                .GET()
                .build();

        HttpResponse<byte[]> response = HTTP_CLIENT.send(
                request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static java.util.List<SearchResult> searchDuckDuckGo(String q)
            throws Exception {

        String html = fetchSearchHtml(
                DDG_BASE + "?q="
                        + URLEncoder.encode(q, StandardCharsets.UTF_8)
                        + "&kl=vn-vi");

        java.util.List<SearchResult> results = new java.util.ArrayList<>();

        Matcher titleMatcher = Pattern.compile(
                "<a\\b[^>]*result__a[^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);

        java.util.List<int[]> spans = new java.util.ArrayList<>();
        java.util.List<String> tags = new java.util.ArrayList<>();
        java.util.List<String> titles = new java.util.ArrayList<>();

        while (titleMatcher.find()) {
            spans.add(new int[]{titleMatcher.start(), titleMatcher.end()});
            tags.add(titleMatcher.group());
            titles.add(titleMatcher.group(1));
        }

        for (int i = 0; i < spans.size() && results.size() < 10; i++) {
            String href = attrValue(tags.get(i), "href");
            String target = decodeDuckDuckGoLink(href);

            if (target == null) {
                continue; // quang cao hoac link khong hop le
            }

            int segmentEnd = i + 1 < spans.size()
                    ? spans.get(i + 1)[0]
                    : Math.min(html.length(), spans.get(i)[1] + 4000);
            String segment = html.substring(spans.get(i)[1], segmentEnd);

            String snippet = "";
            Matcher snippetMatcher = Pattern.compile(
                    "<(a|td|div|span)\\b[^>]*result__snippet[^>]*>(.*?)</\\1>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(segment);
            if (snippetMatcher.find()) {
                snippet = cleanText(snippetMatcher.group(2));
            }

            String title = cleanText(titles.get(i));
            if (!title.isEmpty()) {
                results.add(new SearchResult(title, target, snippet));
            }
        }

        return results;
    }

    private static String decodeDuckDuckGoLink(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }

        String value = unescapeHtml(href.trim());

        if (value.contains("duckduckgo.com/y.js")) {
            return null; // quang cao
        }

        int index = value.indexOf("uddg=");
        if (index >= 0) {
            String encoded = value.substring(index + 5);
            int end = encoded.indexOf('&');
            if (end >= 0) {
                encoded = encoded.substring(0, end);
            }
            value = urlDecode(encoded);
        } else if (value.startsWith("//")) {
            value = "https:" + value;
        }

        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;
        }
        if (lower.contains("://duckduckgo.com/")
                || lower.contains("://html.duckduckgo.com/")) {
            return null;
        }
        return value;
    }

    private static java.util.List<SearchResult> searchBing(String q)
            throws Exception {

        String html = fetchSearchHtml(
                BING_BASE + "?q="
                        + URLEncoder.encode(q, StandardCharsets.UTF_8)
                        + "&setlang=vi&cc=VN");

        java.util.List<SearchResult> results = new java.util.ArrayList<>();

        String[] blocks = html.split("(?i)<li[^>]*class=\"[^\"]*b_algo[^\"]*\"");

        for (int i = 1; i < blocks.length && results.size() < 10; i++) {
            String block = blocks[i];

            Matcher link = Pattern.compile(
                    "<h2[^>]*>\\s*<a\\b([^>]*)>(.*?)</a>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(block);
            if (!link.find()) {
                continue;
            }

            String target = decodeBingLink(attrValue(link.group(0), "href"));
            if (target == null) {
                continue;
            }

            String snippet = "";
            Matcher p = Pattern.compile(
                    "<p\\b[^>]*>(.*?)</p>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(block);
            if (p.find()) {
                snippet = cleanText(p.group(1));
            }

            String title = cleanText(link.group(2));
            if (!title.isEmpty()) {
                results.add(new SearchResult(title, target, snippet));
            }
        }

        return results;
    }

    private static String decodeBingLink(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }

        String value = unescapeHtml(href.trim());

        // Bing boc link: https://www.bing.com/ck/a?...&u=a1<base64url>&...
        if (value.contains("bing.com/ck/a")) {
            int index = value.indexOf("&u=");
            if (index < 0) {
                index = value.indexOf("?u=");
            }
            if (index >= 0) {
                String encoded = value.substring(index + 3);
                int end = encoded.indexOf('&');
                if (end >= 0) {
                    encoded = encoded.substring(0, end);
                }
                if (encoded.startsWith("a1")) {
                    encoded = encoded.substring(2);
                }
                try {
                    value = new String(
                            java.util.Base64.getUrlDecoder().decode(encoded),
                            StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }

        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;
        }
        if (lower.contains("://www.bing.com/")) {
            return null;
        }
        return value;
    }

    private static String attrValue(String tag, String name) {
        Matcher m = Pattern.compile(
                "\\b" + name + "\\s*=\\s*([\"'])(.*?)\\1",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(tag);
        return m.find() ? m.group(2) : null;
    }

    private static String cleanText(String html) {
        String text = html == null ? "" : html.replaceAll("<[^>]+>", "");
        return unescapeHtml(text).replaceAll("\\s+", " ").trim();
    }

    private static String unescapeHtml(String value) {
        String s = value
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .replace("&#39;", "'")
                .replace("&apos;", "'");

        Matcher m = Pattern.compile(
                "&#([xX][0-9a-fA-F]{1,6}|\\d{1,7});").matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement;
            try {
                String num = m.group(1);
                int code = (num.startsWith("x") || num.startsWith("X"))
                        ? Integer.parseInt(num.substring(1), 16)
                        : Integer.parseInt(num);
                replacement = new String(Character.toChars(code));
            } catch (Exception e) {
                replacement = "";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);

        return out.toString().replace("&amp;", "&");
    }

    /** Link mo trong khung trinh duyet: YouTube vao /youtube, con lai vao /proxy. */
    private static String frameHref(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        String encoded = URLEncoder.encode(url, StandardCharsets.UTF_8);

        if (lower.contains("://www.youtube.com/")
                || lower.contains("://youtube.com/")
                || lower.contains("://m.youtube.com/")
                || lower.contains("://youtu.be/")) {
            return "/youtube?url=" + encoded;
        }
        return "/proxy?url=" + encoded;
    }

    private static String displayUrl(String url) {
        String shown = url.replaceFirst("(?i)^https?://", "");
        return shown.length() > 90 ? shown.substring(0, 90) + "..." : shown;
    }

    /** Neu URL la trang tim kiem cua Google/DuckDuckGo/Bing thi tra ve tu khoa, nguoc lai null. */
    static String extractSearchQuery(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return null;
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        boolean isSearchPage =
                (host.endsWith("google.com") && path.equals("/search"))
                        || (host.endsWith("bing.com") && path.equals("/search"))
                        || (host.endsWith("duckduckgo.com")
                        && (path.isEmpty() || path.equals("/")
                        || path.equals("/html/")));

        if (!isSearchPage) {
            return null;
        }

        String q = parseQuery(uri.getRawQuery()).get("q");
        return q == null || q.isBlank() ? null : q.trim();
    }

    private static String buildSearchPage(String q, SearchOutcome outcome) {
        StringBuilder sb = new StringBuilder(8192);

        sb.append("<!DOCTYPE html><html lang=\"vi\"><head>")
                .append("<meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>")
                .append(q.isEmpty() ? "Tim kiem" : "Tim kiem: " + escapeHtml(q))
                .append("</title><style>")
                .append("*{box-sizing:border-box}")
                .append("html,body{margin:0;background:#fff;color:#1f2937;font-family:'Segoe UI',Arial,sans-serif}")
                .append(".wrap{max-width:820px;margin:0 auto;padding:18px 22px 40px}")
                .append("form{display:flex;gap:8px;margin-bottom:22px}")
                .append("input{flex:1;min-width:0;height:44px;border:1px solid #cbd5e1;border-radius:999px;padding:0 18px;font-size:15px}")
                .append("input:focus{outline:3px solid rgba(37,99,235,.25);border-color:#2563eb}")
                .append("button{height:44px;border:0;border-radius:999px;background:#2563eb;color:#fff;padding:0 22px;font-size:15px;font-weight:600;cursor:pointer}")
                .append(".r{margin:0 0 22px}")
                .append(".u{font-size:13px;color:#15803d;margin-bottom:2px;word-break:break-all}")
                .append(".t{font-size:19px;line-height:1.3;color:#1d4ed8;text-decoration:none}")
                .append(".t:hover{text-decoration:underline}")
                .append(".s{margin-top:4px;font-size:14.5px;line-height:1.55;color:#374151}")
                .append(".msg{padding:16px 18px;border-radius:10px;background:#f1f5f9;line-height:1.6}")
                .append(".err{background:#fef2f2;color:#991b1b;border:1px solid #fecaca}")
                .append(".src{margin-top:26px;font-size:12.5px;color:#6b7280}")
                .append("</style></head><body><div class=\"wrap\">");

        sb.append("<form action=\"/search\" method=\"get\">")
                .append("<input name=\"q\" value=\"")
                .append(escapeHtml(q))
                .append("\" placeholder=\"Tim kiem tren web...\" autocomplete=\"off\" autofocus>")
                .append("<button type=\"submit\">Tim kiem</button></form>");

        if (q.isEmpty()) {
            sb.append("<div class=\"msg\">Nhap tu khoa de tim kiem.</div>");
        } else if (outcome.results().isEmpty()) {
            sb.append("<div class=\"msg err\">Khong lay duoc ket qua cho <b>")
                    .append(escapeHtml(q))
                    .append("</b>.");
            if (outcome.error() != null) {
                sb.append("<br><small>")
                        .append(escapeHtml(outcome.error()))
                        .append("</small>");
            }
            sb.append("<br>Ban co the thu lai sau it phut, hoac ")
                    .append("<a href=\"https://duckduckgo.com/?q=")
                    .append(URLEncoder.encode(q, StandardCharsets.UTF_8))
                    .append("\" target=\"_blank\" rel=\"noopener\">mo tren DuckDuckGo</a>.</div>");
        } else {
            for (SearchResult r : outcome.results()) {
                sb.append("<div class=\"r\"><div class=\"u\">")
                        .append(escapeHtml(displayUrl(r.url())))
                        .append("</div><a class=\"t\" href=\"")
                        .append(escapeHtml(frameHref(r.url())))
                        .append("\">")
                        .append(escapeHtml(r.title()))
                        .append("</a>");
                if (!r.snippet().isEmpty()) {
                    sb.append("<div class=\"s\">")
                            .append(escapeHtml(r.snippet()))
                            .append("</div>");
                }
                sb.append("</div>");
            }
            sb.append("<div class=\"src\">Nguon ket qua: ")
                    .append(escapeHtml(outcome.provider()))
                    .append("</div>");
        }

        sb.append("</div><script>")
                .append("window.parent&&window.parent.postMessage(")
                .append("{type:'mini-browser-page',title:document.title,url:location.href},'*');")
                .append("</script></body></html>");

        return sb.toString();
    }

    // ---------------- ham ho tro ----------------

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String part : rawQuery.split("&")) {
            int index = part.indexOf('=');
            if (index < 0) {
                result.put(urlDecode(part), "");
            } else {
                result.put(urlDecode(part.substring(0, index)),
                        urlDecode(part.substring(index + 1)));
            }
        }
        return result;
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
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
}