package com.rob579.chromecasttrivia;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Web server running on the Chromecast itself. A phone on the same Wi-Fi
 * opens it to set up rounds, pick the current round and type answers.
 */
class ControlServer extends NanoHTTPD {

    private static final long VIDEO_CACHE_MS = 5 * 60 * 1000;

    private final MainActivity app;

    // Last video list fetched, so reopening the page doesn't hit YouTube again.
    private String cachedLink = null;
    private String cachedVideos = null;
    private long cachedAt = 0;

    ControlServer(MainActivity app, int port) {
        super(port);
        this.app = app;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        boolean post = session.getMethod() == Method.POST;
        try {
            Map<String, String> body = new HashMap<>();
            if (post) {
                session.parseBody(body);
            }

            if (uri.equals("/") || uri.equals("/index.html")) {
                return noCache(newFixedLengthResponse(Response.Status.OK,
                        "text/html; charset=utf-8", app.readAsset("control.html")));
            }
            if (uri.equals("/api/state")) {
                // Pages poll this; if nothing changed since the revision they
                // already have, they get a tiny "no change" reply.
                String have = session.getParms().get("rev");
                long rev = app.getRev();
                String reply = String.valueOf(rev).equals(have)
                        ? "{\"rev\":" + rev + "}" : app.getStateWithRev();
                return noCache(newFixedLengthResponse(Response.Status.OK,
                        "application/json; charset=utf-8", reply));
            }
            if (uri.equals("/api/ops") && post) {
                String json = body.get("postData");
                if (json == null) {
                    return error(Response.Status.BAD_REQUEST, "Empty request");
                }
                return noCache(newFixedLengthResponse(Response.Status.OK,
                        "application/json; charset=utf-8", app.applyOps(json)));
            }
            if (uri.equals("/api/videos")) {
                boolean refresh = session.getParms().containsKey("refresh");
                return noCache(newFixedLengthResponse(Response.Status.OK,
                        "application/json; charset=utf-8", videos(refresh)));
            }
            if (uri.equals("/api/cmd") && post) {
                Map<String, String> params = session.getParms();
                String name = params.get("c");
                int seconds = 0;
                try {
                    seconds = Integer.parseInt(params.get("n"));
                } catch (Exception ignored) {
                }
                seconds = Math.max(-600, Math.min(600, seconds));
                app.runCommand(name == null ? "" : name, seconds);
                return noCache(newFixedLengthResponse(Response.Status.OK, "text/plain", "ok"));
            }
            return error(Response.Status.NOT_FOUND, "Not found");
        } catch (Exception e) {
            return error(Response.Status.INTERNAL_ERROR, String.valueOf(e.getMessage()));
        }
    }

    /** The channel's or playlist's recent videos as JSON, or an error message. */
    private synchronized String videos(boolean refresh) throws Exception {
        String link = new JSONObject(app.getState()).optString("listUrl", "");
        JSONObject out = new JSONObject();
        if (link.isEmpty()) {
            out.put("videos", new JSONArray());
            return out.toString();
        }
        boolean fresh = link.equals(cachedLink) && cachedVideos != null
                && System.currentTimeMillis() - cachedAt < VIDEO_CACHE_MS;
        if (fresh && !refresh) {
            return cachedVideos;
        }
        try {
            VideoFeed.Result result = VideoFeed.load(link);
            JSONArray list = new JSONArray();
            for (VideoFeed.Video v : result.videos) {
                JSONObject o = new JSONObject();
                o.put("id", v.id);
                o.put("title", v.title);
                o.put("published", v.published);
                list.put(o);
            }
            out.put("title", result.title);
            out.put("videos", list);
            cachedLink = link;
            cachedVideos = out.toString();
            cachedAt = System.currentTimeMillis();
            return cachedVideos;
        } catch (VideoFeed.FeedException e) {
            out.put("error", e.getMessage());
            return out.toString();
        }
    }

    private static Response noCache(Response r) {
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private static Response error(Response.Status status, String message) {
        return newFixedLengthResponse(status, "text/plain; charset=utf-8", message);
    }
}
