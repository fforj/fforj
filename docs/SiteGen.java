import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The fforj documentation site generator. Single file, zero dependencies, run with
 * `java docs/SiteGen.java` (Java 21+) — the same copy-paste-friendly ethos as the
 * library itself.
 *
 * <p>Content comes from "doc-tests" in src/test/java/dev/fforj/docs/*DocTest.java:
 * prose lives in /// markdown comment blocks, examples are the bodies of real @Test
 * methods (so CI proves every example compiles and behaves), and members marked with
 * a preceding `// site:include` line are rendered as code too. The generator
 * interleaves prose and code in source order and renders a static site into
 * build/site, folding the Javadoc output in under /api/.
 *
 * <p>Format summary:
 * <ul>
 *   <li>First /// block starts with a `---` ... `---` front matter: title, slug,
 *       order, summary.</li>
 *   <li>A prose line that is exactly `[landing]` flags the next example as the
 *       homepage snippet (the line itself is dropped).</li>
 *   <li>Brace counting ignores string contents only heuristically — don't put
 *       unbalanced braces inside string literals in doc-tests.</li>
 * </ul>
 */
public class SiteGen {

    record Segment(String kind, String text) {} // kind: "md" | "code"

    record Article(String title, String slug, int order, String summary,
                   String sourceFile, List<Segment> segments) {}

    record Landing(Article article, String code) {}

    static Landing landing;

    public static void main(String[] args) throws IOException {
        var opts = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            opts.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        var version = opts.getOrDefault("version", "0.1.0");
        var docsDir = Path.of(opts.getOrDefault("docs", "src/test/java/dev/fforj/docs"));
        var out = Path.of(opts.getOrDefault("out", "build/site"));
        var javadoc = Path.of(opts.getOrDefault("javadoc", "build/docs/javadoc"));

        List<Article> articles;
        try (Stream<Path> files = Files.list(docsDir)) {
            articles = new ArrayList<>(files
                    .filter(p -> p.getFileName().toString().endsWith("DocTest.java"))
                    .map(SiteGen::parseArticle)
                    .sorted(Comparator.comparingInt(Article::order))
                    .toList());
        }
        if (landing == null) {
            throw new IllegalStateException("no [landing] example found in any doc-test");
        }

        Files.createDirectories(out);
        Files.writeString(out.resolve("style.css"), CSS);
        Files.writeString(out.resolve(".nojekyll"), "");
        Files.writeString(out.resolve("index.html"), landingPage(articles, version));
        for (var article : articles) {
            var dir = out.resolve("guides").resolve(article.slug());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("index.html"), articlePage(article, articles));
        }
        if (Files.isDirectory(javadoc)) {
            copyTree(javadoc, out.resolve("api"));
        } else {
            System.err.println("warning: no javadoc at " + javadoc + "; /api/ not generated");
        }
        System.out.println("site: " + out.toAbsolutePath()
                + " (" + articles.size() + " guides, version " + version + ")");
    }

    // ------------------------------------------------------------------
    // Parsing doc-tests
    // ------------------------------------------------------------------

    static Article parseArticle(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var meta = new LinkedHashMap<String, String>();
        var segments = new ArrayList<Segment>();
        var prose = new ArrayList<String>();
        boolean metaDone = false;
        boolean landingNext = false;
        String pendingLandingCode = null;

        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            var stripped = line.strip();

            if (stripped.startsWith("///")) {
                var text = stripped.substring(3);
                if (text.startsWith(" ")) {
                    text = text.substring(1);
                }
                prose.add(text);
                continue;
            }

            if (stripped.equals("// site:include")) {
                landingNext |= flushProse(prose, segments, meta, metaDone);
                metaDone = true;
                i = captureMember(lines, i + 1, segments);
                continue;
            }

            if (stripped.startsWith("@Test")) {
                landingNext |= flushProse(prose, segments, meta, metaDone);
                metaDone = true;
                i = captureTestBody(lines, i + 1, segments);
                if (landingNext) {
                    pendingLandingCode = segments.getLast().text();
                    landingNext = false;
                }
            }
        }
        flushProse(prose, segments, meta, metaDone);

        var article = new Article(
                meta.getOrDefault("title", file.getFileName().toString()),
                meta.getOrDefault("slug", file.getFileName().toString().toLowerCase()),
                Integer.parseInt(meta.getOrDefault("order", "99")),
                meta.getOrDefault("summary", ""),
                file.getFileName().toString(),
                segments);
        if (pendingLandingCode != null) {
            landing = new Landing(article, pendingLandingCode);
        }
        return article;
    }

    /** Returns true if the flushed prose carried the [landing] marker. */
    static boolean flushProse(List<String> prose, List<Segment> segments,
                              Map<String, String> meta, boolean metaDone) {
        if (prose.isEmpty()) {
            return false;
        }
        var lines = new ArrayList<>(prose);
        prose.clear();
        if (!metaDone && !lines.isEmpty() && lines.getFirst().strip().equals("---")) {
            int end = lines.subList(1, lines.size()).indexOf("---") + 1;
            for (var kv : lines.subList(1, end)) {
                int colon = kv.indexOf(':');
                if (colon > 0) {
                    meta.put(kv.substring(0, colon).strip(), kv.substring(colon + 1).strip());
                }
            }
            lines.subList(0, end + 1).clear();
        }
        boolean landingMarker = lines.removeIf(l -> l.strip().equals("[landing]"));
        var text = String.join("\n", lines).strip();
        if (!text.isEmpty()) {
            segments.add(new Segment("md", text));
        }
        return landingMarker;
    }

    /** Captures a member (record/method/interface) after a site:include marker. */
    static int captureMember(List<String> lines, int start, List<Segment> segments) {
        var captured = new ArrayList<String>();
        int depth = 0;
        boolean sawBrace = false;
        int i = start;
        for (; i < lines.size(); i++) {
            var line = lines.get(i);
            captured.add(line);
            for (char c : line.toCharArray()) {
                if (c == '{') { depth++; sawBrace = true; }
                if (c == '}') { depth--; }
            }
            if (sawBrace && depth == 0) {
                break;
            }
            if (!sawBrace && line.strip().endsWith(";")) {
                break;
            }
        }
        segments.add(new Segment("code", dedent(captured)));
        return i;
    }

    /** Captures the body of a @Test method, excluding the signature and closing brace. */
    static int captureTestBody(List<String> lines, int start, List<Segment> segments) {
        int i = start;
        while (i < lines.size() && !lines.get(i).contains("(")) {
            i++;
        }
        // Signature line: assume it opens the body brace.
        int depth = 0;
        for (char c : lines.get(i).toCharArray()) {
            if (c == '{') { depth++; }
            if (c == '}') { depth--; }
        }
        var body = new ArrayList<String>();
        for (i++; i < lines.size(); i++) {
            var line = lines.get(i);
            int before = depth;
            for (char c : line.toCharArray()) {
                if (c == '{') { depth++; }
                if (c == '}') { depth--; }
            }
            if (depth == 0 && before == 1) {
                break; // the method's closing brace
            }
            body.add(line);
        }
        segments.add(new Segment("code", dedent(body)));
        return i;
    }

    static String dedent(List<String> lines) {
        while (!lines.isEmpty() && lines.getFirst().isBlank()) {
            lines.removeFirst();
        }
        while (!lines.isEmpty() && lines.getLast().isBlank()) {
            lines.removeLast();
        }
        int indent = lines.stream()
                .filter(l -> !l.isBlank())
                .mapToInt(l -> l.length() - l.stripLeading().length())
                .min().orElse(0);
        return lines.stream()
                .map(l -> l.isBlank() ? "" : l.substring(Math.min(indent, l.length())))
                .reduce((a, b) -> a + "\n" + b).orElse("");
    }

    // ------------------------------------------------------------------
    // Markdown subset -> HTML
    // ------------------------------------------------------------------

    static String md(String text) {
        var html = new StringBuilder();
        var para = new ArrayList<String>();
        boolean inList = false;
        for (var line : (text + "\n").split("\n", -1)) {
            var s = line.strip();
            if (s.startsWith("### ")) {
                inList = closeParaAndList(html, para, inList);
                html.append("<h3 id=\"").append(slugify(s.substring(4))).append("\">")
                        .append(inline(s.substring(4))).append("</h3>\n");
            } else if (s.startsWith("## ")) {
                inList = closeParaAndList(html, para, inList);
                html.append("<h2 id=\"").append(slugify(s.substring(3))).append("\">")
                        .append(inline(s.substring(3))).append("</h2>\n");
            } else if (s.startsWith("- ")) {
                if (!para.isEmpty()) {
                    html.append("<p>").append(inline(String.join(" ", para))).append("</p>\n");
                    para.clear();
                }
                if (!inList) {
                    html.append("<ul>\n");
                    inList = true;
                }
                html.append("<li>").append(inline(s.substring(2))).append("</li>\n");
            } else if (s.isEmpty()) {
                inList = closeParaAndList(html, para, inList);
            } else {
                para.add(s);
            }
        }
        closeParaAndList(html, para, inList);
        return html.toString();
    }

    static boolean closeParaAndList(StringBuilder html, List<String> para, boolean inList) {
        if (!para.isEmpty()) {
            html.append("<p>").append(inline(String.join(" ", para))).append("</p>\n");
            para.clear();
        }
        if (inList) {
            html.append("</ul>\n");
        }
        return false;
    }

    static String inline(String s) {
        s = escape(s);
        // Protect inline code spans from further formatting.
        var codes = new ArrayList<String>();
        var m = java.util.regex.Pattern.compile("`([^`]+)`").matcher(s);
        var sb = new StringBuilder();
        while (m.find()) {
            codes.add("<code>" + m.group(1) + "</code>");
            m.appendReplacement(sb, " " + (codes.size() - 1) + " ");
        }
        m.appendTail(sb);
        s = sb.toString();
        s = s.replaceAll("\\[([^]]+)]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        s = s.replaceAll("\\*([^*]+)\\*", "<em>$1</em>");
        for (int i = 0; i < codes.size(); i++) {
            s = s.replace(" " + i + " ", codes.get(i));
        }
        return s;
    }

    static String slugify(String s) {
        return s.toLowerCase().replaceAll("`", "").replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------
    // Java syntax highlighting (comments, strings, annotations, keywords)
    // ------------------------------------------------------------------

    static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "continue", "default", "do", "double", "else", "enum", "extends",
            "final", "finally", "float", "for", "if", "implements", "import",
            "instanceof", "int", "interface", "long", "new", "package", "private",
            "protected", "public", "record", "return", "sealed", "short", "static",
            "super", "switch", "synchronized", "this", "throw", "throws", "try", "var",
            "void", "volatile", "while", "yield", "permits", "non-sealed");

    static String highlight(String code) {
        var out = new StringBuilder();
        int i = 0;
        int n = code.length();
        while (i < n) {
            char c = code.charAt(i);
            if (c == '/' && i + 1 < n && code.charAt(i + 1) == '/') {
                int end = code.indexOf('\n', i);
                if (end < 0) end = n;
                out.append("<span class=\"c-com\">").append(escape(code.substring(i, end)))
                        .append("</span>");
                i = end;
            } else if (c == '"') {
                int end = i + 1;
                while (end < n && (code.charAt(end) != '"' || code.charAt(end - 1) == '\\')) {
                    end++;
                }
                end = Math.min(end + 1, n);
                out.append("<span class=\"c-str\">").append(escape(code.substring(i, end)))
                        .append("</span>");
                i = end;
            } else if (c == '@' && i + 1 < n && Character.isJavaIdentifierStart(code.charAt(i + 1))) {
                int end = i + 1;
                while (end < n && Character.isJavaIdentifierPart(code.charAt(end))) {
                    end++;
                }
                out.append("<span class=\"c-ann\">").append(code, i, end).append("</span>");
                i = end;
            } else if (Character.isJavaIdentifierStart(c)) {
                int end = i;
                while (end < n && Character.isJavaIdentifierPart(code.charAt(end))) {
                    end++;
                }
                var word = code.substring(i, end);
                if (KEYWORDS.contains(word)) {
                    out.append("<span class=\"c-kw\">").append(word).append("</span>");
                } else {
                    out.append(word);
                }
                i = end;
            } else {
                out.append(escape(String.valueOf(c)));
                i++;
            }
        }
        return out.toString();
    }

    static String codeBlock(String code) {
        return "<pre class=\"code\"><code>" + highlight(code) + "</code></pre>\n";
    }

    // ------------------------------------------------------------------
    // Pages
    // ------------------------------------------------------------------

    static String shell(String root, String title, String description, String body) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title>
                <meta name="description" content="%s">
                <link rel="icon" href="data:image/svg+xml,<svg xmlns=%%22http://www.w3.org/2000/svg%%22 viewBox=%%220 0 100 100%%22><text y=%%22.9em%%22 font-size=%%2290%%22 font-family=%%22Georgia,serif%%22 fill=%%22%%23C0341D%%22>ﬀ</text></svg>">
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Newsreader:ital,opsz,wght@0,6..72,400..700;1,6..72,400..700&family=JetBrains+Mono:wght@400;500;700&display=swap" rel="stylesheet">
                <link rel="stylesheet" href="%sstyle.css">
                </head>
                <body>
                <header class="top">
                  <a class="wordmark" href="%sindex.html"><span class="lig">ﬀ</span>orj</a>
                  <nav>
                    <a href="%sindex.html#guides">Guides</a>
                    <a href="%sapi/index.html">API</a>
                    <a href="https://github.com/fforj/fforj">GitHub</a>
                  </nav>
                </header>
                %s
                <footer class="foot">
                  <p><span class="lig rub">ﬀ</span>orj is MIT-licensed.
                  Every example on this site is a test in the
                  <a href="https://github.com/fforj/fforj">repository</a> —
                  the suite ran green before this page was built.</p>
                </footer>
                </body>
                </html>
                """.formatted(escape(title), escape(description), root, root, root, root, body);
    }

    static String landingPage(List<Article> articles, String version) {
        var body = new StringBuilder();
        body.append("""
                <main class="landing">
                <section class="hero">
                  <div class="specimen">
                    <div class="glyph">ﬀ</div>
                    <div class="caption">U+FB00 &middot; LATIN SMALL LIGATURE FF</div>
                  </div>
                  <div class="thesis">
                    <h1><span class="rub">f</span>unctional <span class="rub">f</span>or Java</h1>
                    <p class="sub">Four small types you'd otherwise re-implement in every project:
                    <code>Result</code>, <code>Validated</code>, <code>NonEmptyList</code>,
                    <code>Retry</code>. Java 21+. Zero runtime dependencies. A ligature's worth
                    of library: two things — functional types and plain Java — set as one glyph.</p>
                  </div>
                </section>

                <section class="proof">
                  <div class="eyebrow">PROVEN, NOT PROMISED</div>
                  <p>This example is a test. It ran, and passed, before this page was built:</p>
                """);
        body.append(codeBlock(landing.code()));
        body.append("<p class=\"more\"><a href=\"guides/").append(landing.article().slug())
                .append("/index.html\">Read the ").append(escape(landing.article().title()))
                .append(" guide &rarr;</a></p>\n</section>\n");

        body.append("<section id=\"guides\" class=\"guides\">\n<div class=\"eyebrow\">GUIDES</div>\n<div class=\"cards\">\n");
        for (var a : articles) {
            body.append("<a class=\"card\" href=\"guides/").append(a.slug()).append("/index.html\">")
                    .append("<h3>").append(inline(a.title())).append("</h3>")
                    .append("<p>").append(inline(a.summary())).append("</p>")
                    .append("</a>\n");
        }
        body.append("</div>\n</section>\n");

        body.append("""
                <section class="install">
                  <div class="eyebrow">GET IT</div>
                """);
        body.append(codeBlock("// Gradle\nimplementation(\"dev.fforj:fforj:" + version + "\")"));
        body.append("""
                  <p>Or copy the source of the types you need into your project — every class
                  stands alone by design, and behaves identically either way. The
                  <a href="api/index.html">API reference</a> is the full Javadoc.</p>
                </section>

                <section class="ethos">
                  <div class="eyebrow">DELIBERATELY NOT HERE</div>
                  <ul>
                    <li><strong>No IO monad</strong> — virtual threads and structured concurrency already solve it.</li>
                    <li><strong>No tuples</strong> — records exist.</li>
                    <li><strong>No collections library</strong> — the stdlib is fine.</li>
                    <li><strong>No type classes</strong> — Java's type system doesn't want them.</li>
                  </ul>
                  <p>The surface area is bounded on purpose. This library will not grow into the
                  thing it replaces.</p>
                </section>
                </main>
                """);
        return shell("", "ﬀorj — functional for Java",
                "Result, Validated, NonEmptyList, Retry for Java 21+. Zero dependencies.",
                body.toString());
    }

    static String articlePage(Article article, List<Article> all) {
        var body = new StringBuilder();
        body.append("<div class=\"layout\">\n<aside class=\"sidenav\">\n<div class=\"eyebrow\">GUIDES</div>\n<ul>\n");
        for (var a : all) {
            body.append("<li").append(a.slug().equals(article.slug()) ? " class=\"here\"" : "")
                    .append("><a href=\"../").append(a.slug()).append("/index.html\">")
                    .append(inline(a.title())).append("</a></li>\n");
        }
        body.append("</ul>\n</aside>\n<main class=\"article\">\n");
        body.append("<h1>").append(inline(article.title())).append("</h1>\n");
        for (var seg : article.segments()) {
            body.append(seg.kind().equals("md") ? md(seg.text()) : codeBlock(seg.text()));
        }
        body.append("<p class=\"edit\"><a href=\"https://github.com/fforj/fforj/blob/main/src/test/java/dev/fforj/docs/")
                .append(article.sourceFile())
                .append("\">This page is generated from a test file — read or improve it on GitHub.</a></p>\n");
        body.append("</main>\n</div>\n");
        return shell("../../", article.title() + " — ﬀorj", article.summary(), body.toString());
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            walk.forEach(src -> {
                try {
                    var dst = to.resolve(from.relativize(src).toString());
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // Styles: rubrication on cool paper. Newsreader for prose, JetBrains
    // Mono (ligatures on — the theme demands it) for code.
    // ------------------------------------------------------------------

    static final String CSS = """
            :root {
              --paper: #FCFBF8; --ink: #1C1A16; --rubric: #C0341D; --muted: #6F6A5F;
              --rule: #E6E2D8; --code-bg: #F5F3EC; --card: #FFFFFF;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --paper: #181715; --ink: #E8E4DB; --rubric: #E25B3C; --muted: #9A947F;
                --rule: #2E2B26; --code-bg: #211F1B; --card: #1E1C19;
              }
            }
            * { box-sizing: border-box; margin: 0; }
            html { -webkit-text-size-adjust: 100%; }
            body {
              background: var(--paper); color: var(--ink);
              font-family: "Newsreader", Georgia, serif;
              font-optical-sizing: auto; font-size: 1.125rem; line-height: 1.65;
            }
            a { color: inherit; text-decoration-color: var(--rubric); text-decoration-thickness: 1px; text-underline-offset: 3px; }
            a:hover { color: var(--rubric); }
            code, pre {
              font-family: "JetBrains Mono", ui-monospace, monospace;
              font-variant-ligatures: contextual;
            }
            p > code, li > code, h1 code, h2 code, h3 code {
              font-size: 0.82em; background: var(--code-bg);
              padding: 0.08em 0.35em; border-radius: 4px;
            }
            .rub { color: var(--rubric); }
            .lig { color: var(--rubric); }
            .eyebrow {
              font-family: "JetBrains Mono", monospace; font-size: 0.72rem;
              letter-spacing: 0.18em; color: var(--rubric); margin-bottom: 0.9rem;
            }

            .top {
              display: flex; justify-content: space-between; align-items: baseline;
              padding: 1.4rem clamp(1.2rem, 5vw, 4rem);
              border-bottom: 1px solid var(--rule);
            }
            .wordmark { font-size: 1.5rem; font-weight: 600; text-decoration: none; }
            .top nav { display: flex; gap: 1.6rem; }
            .top nav a { text-decoration: none; font-size: 1rem; color: var(--muted); }
            .top nav a:hover { color: var(--rubric); }

            .landing { max-width: 68rem; margin: 0 auto; padding: 0 clamp(1.2rem, 5vw, 4rem); }
            .hero {
              display: grid; grid-template-columns: minmax(12rem, 1fr) 2fr;
              gap: clamp(1.5rem, 5vw, 4rem); align-items: center;
              padding: clamp(2.5rem, 8vh, 6rem) 0 3rem;
            }
            .specimen { text-align: center; }
            .glyph {
              font-size: clamp(9rem, 22vw, 16rem); line-height: 0.9;
              color: var(--rubric); font-weight: 500;
              font-feature-settings: "liga", "dlig";
            }
            .caption {
              font-family: "JetBrains Mono", monospace; font-size: 0.68rem;
              letter-spacing: 0.14em; color: var(--muted); margin-top: 1rem;
            }
            .thesis h1 {
              font-size: clamp(2.2rem, 5.5vw, 3.6rem); font-weight: 500;
              line-height: 1.08; letter-spacing: -0.01em; margin-bottom: 1.1rem;
            }
            .sub { font-size: 1.15rem; color: var(--muted); max-width: 34rem; }

            section { padding: 2.6rem 0; border-top: 1px solid var(--rule); }
            .hero { border-top: none; }

            .code {
              background: var(--code-bg); border: 1px solid var(--rule); border-radius: 8px;
              padding: 1.1rem 1.3rem; overflow-x: auto;
              font-size: 0.86rem; line-height: 1.6; margin: 1.3rem 0;
            }
            .c-kw { font-weight: 700; }
            .c-str { color: var(--rubric); }
            .c-com { color: var(--muted); font-style: italic; }
            .c-ann { color: var(--muted); }

            .cards {
              display: grid; grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
              gap: 1rem;
            }
            .card {
              display: block; background: var(--card); border: 1px solid var(--rule);
              border-radius: 10px; padding: 1.2rem 1.3rem; text-decoration: none;
            }
            .card:hover { border-color: var(--rubric); color: inherit; }
            .card h3 { font-weight: 600; margin-bottom: 0.4rem; }
            .card p { color: var(--muted); font-size: 0.98rem; }

            .ethos ul { padding-left: 1.2rem; margin: 0.6rem 0 1rem; }
            .ethos li { margin: 0.35rem 0; }
            .more a { text-decoration: none; color: var(--rubric); }

            .layout {
              display: grid; grid-template-columns: 15rem minmax(0, 1fr);
              gap: clamp(1.5rem, 4vw, 4rem);
              max-width: 72rem; margin: 0 auto; padding: 2.5rem clamp(1.2rem, 5vw, 4rem);
            }
            .sidenav ul { list-style: none; padding: 0; }
            .sidenav li { margin: 0.45rem 0; font-size: 1rem; }
            .sidenav a { text-decoration: none; color: var(--muted); }
            .sidenav a:hover { color: var(--rubric); }
            .sidenav .here a { color: var(--ink); font-weight: 600; }
            .sidenav .here::before { content: "\\00B6\\00A0"; color: var(--rubric); }

            .article { max-width: 44rem; }
            .article h1 { font-size: 2.3rem; font-weight: 500; line-height: 1.15; margin-bottom: 1rem; }
            .article h2 {
              font-size: 1.45rem; font-weight: 600; margin: 2.4rem 0 0.7rem;
            }
            .article h2::before { content: "\\00B6\\00A0"; color: var(--rubric); font-weight: 400; }
            .article h3 { font-size: 1.15rem; margin: 1.8rem 0 0.5rem; }
            .article p { margin: 0.8rem 0; }
            .article ul { padding-left: 1.3rem; margin: 0.8rem 0; }
            .edit { margin-top: 3rem; font-size: 0.95rem; }
            .edit a { color: var(--muted); }

            .foot {
              border-top: 1px solid var(--rule); margin-top: 3rem;
              padding: 1.6rem clamp(1.2rem, 5vw, 4rem);
              color: var(--muted); font-size: 0.98rem;
            }

            @media (max-width: 760px) {
              .hero { grid-template-columns: 1fr; text-align: left; }
              .specimen { text-align: left; }
              .layout { grid-template-columns: 1fr; }
              .sidenav { border-bottom: 1px solid var(--rule); padding-bottom: 1rem; }
            }
            @media (prefers-reduced-motion: no-preference) {
              .glyph { transition: transform 0.4s ease; }
              .specimen:hover .glyph { transform: translateY(-4px); }
            }
            """;
}
