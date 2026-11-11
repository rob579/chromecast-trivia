package com.rob579.chromecasttrivia;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lists the most recent videos of a YouTube channel or playlist using
 * YouTube's public feed, which needs no account or API key but only
 * returns the latest 15 videos.
 */
final class VideoFeed {

    static final class Video {
        final String id, title, published;
        Video(String id, String title, String published) {
            this.id = id; this.title = title; this.published = published;
        }
    }

    static final class Result {
        String title = "";
        final List<Video> videos = new ArrayList<>();
    }

    /** A problem worth showing to the person as-is. */
    static final class FeedException extends Exception {
        FeedException(String message) { super(message); }
    }

    private static final String FEED = "https://www.youtube.com/feeds/videos.xml?";
    private static final Pattern LIST = Pattern.compile("[?&]list=([\\w-]+)");
    private static final Pattern CHANNEL_PATH = Pattern.compile("/channel/(UC[\\w-]{22})");
    private static final Pattern[] CHANNEL_IN_PAGE = {
            Pattern.compile("feeds/videos\\.xml\\?channel_id=(UC[\\w-]{22})"),
            Pattern.compile("\"externalId\":\"(UC[\\w-]{22})\""),
            Pattern.compile("itemprop=\"(?:channelId|identifier)\" content=\"(UC[\\w-]{22})\""),
            Pattern.compile("\"channelId\":\"(UC[\\w-]{22})\""),
    };

    static Result load(String link) throws FeedException {
        String feedUrl = feedUrlFor(link);
        String xml;
        try {
            xml = httpGet(feedUrl);
        } catch (IOException e) {
            throw new FeedException("YouTube has no video list for that link (" + e.getMessage() + ").");
        }
        Result result = parse(xml);
        if (result.videos.isEmpty()) {
            throw new FeedException("That channel or playlist has no videos listed.");
        }
        return result;
    }

    /** Works out the feed address, looking the channel up on YouTube if needed. */
    static String feedUrlFor(String link) throws FeedException {
        String direct = directFeedUrl(link);
        if (direct != null) return direct;

        String url = normalise(link);
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Exception e) {
            host = null;
        }
        if (host == null || !(host.equals("youtu.be") || host.equals("youtube.com")
                || host.endsWith(".youtube.com"))) {
            throw new FeedException("That doesn't look like a YouTube link.");
        }
        String page;
        try {
            page = httpGet(url);
        } catch (IOException e) {
            throw new FeedException("Couldn't open that link on YouTube (" + e.getMessage() + ").");
        }
        String channelId = channelIdFromPage(page);
        if (channelId == null) {
            throw new FeedException("Couldn't find a channel at that link. Try the channel's "
                    + "page address, or a playlist link.");
        }
        return FEED + "channel_id=" + channelId;
    }

    /** Feed address when the link already contains a playlist or channel ID, else null. */
    static String directFeedUrl(String link) {
        String s = link == null ? "" : link.trim();
        Matcher m = LIST.matcher(s);
        if (m.find()) return FEED + "playlist_id=" + m.group(1);
        m = CHANNEL_PATH.matcher(s);
        if (m.find()) return FEED + "channel_id=" + m.group(1);
        if (s.matches("UC[\\w-]{22}")) return FEED + "channel_id=" + s;
        if (s.matches("(PL|UU|OL|FL)[\\w-]{10,}")) return FEED + "playlist_id=" + s;
        return null;
    }

    static String normalise(String link) {
        String s = link == null ? "" : link.trim();
        if (s.startsWith("@")) return "https://www.youtube.com/" + s;
        if (s.matches("[\\w-]{11}")) return "https://www.youtube.com/watch?v=" + s;
        if (!s.matches("(?i)^https?://.*")) return "https://" + s;
        return s.replaceFirst("(?i)^http://", "https://");
    }

    static String channelIdFromPage(String html) {
        for (Pattern p : CHANNEL_IN_PAGE) {
            Matcher m = p.matcher(html);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    static Result parse(String xml) {
        Result result = new Result();
        String[] parts = xml.split("<entry>");
        result.title = unescape(tag(parts[0], "title"));
        for (int i = 1; i < parts.length; i++) {
            String id = tag(parts[i], "yt:videoId");
            if (id.isEmpty()) continue;
            result.videos.add(new Video(id, unescape(tag(parts[i], "title")), tag(parts[i], "published")));
        }
        return result;
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + "(?:\\s[^>]*)?>(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    static String unescape(String s) {
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            int code = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            m.appendReplacement(out, Matcher.quoteReplacement(new String(Character.toChars(code))));
        }
        m.appendTail(out);
        return out.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    static String httpGet(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36");
        c.setRequestProperty("Accept-Language", "en");
        // Skips the cookie-consent page YouTube shows in some regions.
        c.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+1");
        try {
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("error " + status);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                // Channel pages are large; the ID is always well within this.
                while ((n = in.read(buffer)) != -1 && out.size() < 4_000_000) {
                    out.write(buffer, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private VideoFeed() {}
}
